package com.emmaguy.todayilearned.refresh;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.commonsware.cwac.wakeful.WakefulIntentService;
import com.emmaguy.todayilearned.App;
import com.emmaguy.todayilearned.common.Logger;
import com.emmaguy.todayilearned.common.PocketUtils;
import com.emmaguy.todayilearned.sharedlib.Comment;
import com.emmaguy.todayilearned.sharedlib.Constants;
import com.emmaguy.todayilearned.storage.TokenStorage;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.gson.Gson;

import java.util.List;

import javax.inject.Inject;

import rx.Observer;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import timber.log.Timber;

public class WearListenerService extends WearableListenerService {
    @Inject RedditService mRedditService;

    @Inject TokenStorage mTokenStorage;
    @Inject Gson mGson;

    private GoogleApiClient mGoogleApiClient;

    private static String getVoteType(int voteDirection) {
        return voteDirection > 0 ? Logger.LOG_EVENT_VOTE_UP : Logger.LOG_EVENT_VOTE_DOWN;
    }

    public static void sendToPath(final GoogleApiClient client, final String path) {
        Wearable.MessageApi.sendMessage(client, "", path, null)
                .setResultCallback(sendMessageResult -> Timber.d("sendToPath: " + path + " status " + sendMessageResult.getStatus()));
    }

    @Override
    public void onCreate() {
        super.onCreate();

        App.with(this).getAppComponent().inject(this);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Timber.d("onMessageReceived, path: " + messageEvent.getPath());
        if (messageEvent.getPath().equals(Constants.PATH_REFRESH)) {
            WakefulIntentService.sendWakefulWork(this, RetrieveService.getFromWearableIntent(this));
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        dataEvents.close();

        for (DataEvent event : events) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataMap dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

                String path = event.getDataItem().getUri().getPath();

                Timber.d("onDataChanged, path: " + path);
                if (Constants.PATH_REPLY.equals(path)) {
                    String fullname = dataMap.getString(Constants.PATH_KEY_POST_FULLNAME);
                    String message = dataMap.getString(Constants.PATH_KEY_MESSAGE);
                    boolean isDirectMessage = dataMap.getBoolean(Constants.PATH_KEY_IS_DIRECT_MESSAGE);

                    if (isDirectMessage) {
                        String subject = dataMap.getString(Constants.PATH_KEY_MESSAGE_SUBJECT);
                        String toUser = dataMap.getString(Constants.PATH_KEY_MESSAGE_TO_USER);

                        replyToDirectMessage(subject, message, toUser);
                    } else {
                        replyToRedditPost(fullname, message);
                    }
                } else if (Constants.PATH_OPEN_ON_PHONE.equals(path)) {
                    String permalink = dataMap.getString(Constants.KEY_POST_PERMALINK);
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.WEB_URL_REDDIT + permalink));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } else if (Constants.PATH_SAVE_TO_POCKET.equals(path)) {
                    String permalink = dataMap.getString(Constants.KEY_POST_PERMALINK);
                    String url = Constants.WEB_URL_REDDIT + permalink;

                    Intent intent = PocketUtils.newAddToPocketIntent(url, "", this);
                    if (intent == null) {
                        App.with(this).sendEvent(Logger.LOG_EVENT_SAVE_TO_POCKET, Logger.LOG_EVENT_FAILURE);
                        sendToPath(mGoogleApiClient, Constants.PATH_SAVE_TO_POCKET_RESULT_FAILED);
                    } else {
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        App.with(this).sendEvent(Logger.LOG_EVENT_SAVE_TO_POCKET, Logger.LOG_EVENT_SUCCESS);
                        sendToPath(mGoogleApiClient, Constants.PATH_SAVE_TO_POCKET_RESULT_SUCCESS);
                    }
                } else if (Constants.PATH_VOTE.equals(path)) {
                    String fullname = dataMap.getString(Constants.PATH_KEY_POST_FULLNAME);
                    int voteDirection = dataMap.getInt(Constants.KEY_POST_VOTE_DIRECTION);
                    vote(fullname, voteDirection);
                } else if (Constants.PATH_COMMENTS.equals(path)) {
                    String permalink = dataMap.getString(Constants.KEY_POST_PERMALINK);
                    if (!TextUtils.isEmpty(permalink)) {
                        getComments(permalink);
                    }
                } else if (Constants.PATH_LOGGING.equals(path)) {
                    String message = dataMap.getString(Constants.PATH_KEY_MESSAGE);
                    Timber.d(message);
                }
            }
        }
    }

    private void getComments(String permalink) {
        mRedditService.comments(permalink, "best")
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(comments -> {
                    if (comments == null) {
                        App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_GET_COMMENTS, Logger.LOG_EVENT_FAILURE);
                        sendToPath(mGoogleApiClient, Constants.PATH_GET_COMMENTS_RESULT_FAILED);
                    } else {
                        sendComments(comments);
                        App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_GET_COMMENTS, Logger.LOG_EVENT_SUCCESS);
                    }
                }, throwable -> {
                    Timber.e(throwable, "Failed to get comments");
                    App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_GET_COMMENTS, Logger.LOG_EVENT_FAILURE);
                    sendToPath(mGoogleApiClient, Constants.PATH_GET_COMMENTS_RESULT_FAILED);
                });
    }

    private void sendComments(final List<Comment> comments) {
        PutDataMapRequest mapRequest = PutDataMapRequest.create(Constants.PATH_COMMENTS);
        mapRequest.getDataMap().putString(Constants.KEY_REDDIT_POSTS, mGson.toJson(comments));
        mapRequest.getDataMap().putLong("timestamp", System.currentTimeMillis());

        PutDataRequest request = mapRequest.asPutDataRequest();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                .setResultCallback(dataItemResult -> Timber.d("Sent " + comments.size() + " comments onResult: " + dataItemResult.getStatus()));
    }

    private void vote(String fullname, final int voteDirection) {
        mRedditService.vote(fullname, voteDirection)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(v -> {

                }, e -> {
                    Timber.e(e, "Failed to vote");
                    App.with(WearListenerService.this).sendEvent(getVoteType(voteDirection), Logger.LOG_EVENT_FAILURE);
                    sendToPath(mGoogleApiClient, Constants.PATH_VOTE_RESULT_FAILED);
                }, () -> {
                    App.with(WearListenerService.this).sendEvent(getVoteType(voteDirection), Logger.LOG_EVENT_SUCCESS);
                    sendToPath(mGoogleApiClient, Constants.PATH_VOTE_RESULT_SUCCESS);
                });
    }

    private void replyToDirectMessage(String subject, String message, String toUser) {
        mRedditService.replyToDirectMessage(subject, message, toUser)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<RedditResponse>() {
                    @Override
                    public void onNext(RedditResponse response) {
                        if (response.hasErrors()) {
                            throw new RuntimeException("Failed to reply to DM: " + response);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_SEND_DM, Logger.LOG_EVENT_SUCCESS);
                        sendToPath(mGoogleApiClient, Constants.PATH_POST_REPLY_RESULT_SUCCESS);
                    }

                    @Override
                    public void onError(Throwable e) {
                        App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_SEND_DM, Logger.LOG_EVENT_FAILURE);
                        Timber.e(e, "Failed to reply to direct message");
                        sendToPath(mGoogleApiClient, Constants.PATH_POST_REPLY_RESULT_FAILURE);
                    }
                });
    }

    private void replyToRedditPost(String fullname, String message) {
        mRedditService.commentOnPost(message, fullname)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<RedditResponse>() {
                    @Override
                    public void onNext(RedditResponse response) {
                        if (response.hasErrors()) {
                            throw new RuntimeException("Failed to comment on post: " + response);
                        }
                    }

                    @Override
                    public void onCompleted() {
                        sendToPath(mGoogleApiClient, Constants.PATH_POST_REPLY_RESULT_SUCCESS);
                        App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_REPLY_TO_POST, Logger.LOG_EVENT_SUCCESS);
                    }

                    @Override
                    public void onError(Throwable e) {
                        App.with(WearListenerService.this).sendEvent(Logger.LOG_EVENT_REPLY_TO_POST, Logger.LOG_EVENT_FAILURE);
                        Timber.e(e, "Failed to reply to reddit post");
                        sendToPath(mGoogleApiClient, Constants.PATH_POST_REPLY_RESULT_FAILURE);
                    }
                });
    }
}

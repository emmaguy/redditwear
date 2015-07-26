package com.emmaguy.todayilearned.refresh;

import android.content.res.Resources;

import com.emmaguy.todayilearned.storage.UserStorage;
import com.google.gson.Gson;

import java.lang.reflect.Type;

import retrofit.converter.ConversionException;
import retrofit.converter.Converter;
import retrofit.converter.GsonConverter;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedOutput;

public class PostConverter implements Converter {
    private final GsonConverter mOriginalConverter;
    private final UserStorage mUserStorage;
    private final Resources mResources;
    private final Gson mGson;

    public PostConverter(Gson gson, GsonConverter gsonConverter, Resources resources, UserStorage userStorage) {
        mGson = gson;
        mOriginalConverter = gsonConverter;
        mResources = resources;
        mUserStorage = userStorage;
    }

    @Override
    public Object fromBody(TypedInput body, Type type) throws ConversionException {
        ListingResponse listingResponse = (ListingResponse) mOriginalConverter.fromBody(body, ListingResponse.class);
        return new ListingResponseConverter(mUserStorage, mResources).convert(listingResponse);
    }

    @Override
    public TypedOutput toBody(Object object) {
        return mOriginalConverter.toBody(object);
    }
}
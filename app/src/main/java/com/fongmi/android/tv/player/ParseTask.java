package com.fongmi.android.tv.player;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.ApiConfig;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.net.OkHttp;
import com.fongmi.android.tv.ui.custom.CustomWebView;
import com.fongmi.android.tv.utils.Json;
import com.fongmi.android.tv.utils.Utils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Headers;
import okhttp3.Response;

public class ParseTask {

    private ExecutorService executor;
    private CustomWebView webView;
    private Callback callback;
    private Parse parse;

    public static ParseTask create(Callback callback) {
        return new ParseTask(callback);
    }

    public ParseTask(Callback callback) {
        this.executor = Executors.newSingleThreadExecutor();
        this.callback = callback;
    }

    public ParseTask run(Result result, boolean useParse) {
        setParse(result, useParse);
        executor.execute(() -> doInBackground(result.getKey(), result.getUrl(), result.getFlag()));
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = ApiConfig.get().getParse();
        else if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        else if (result.getPlayUrl().startsWith("parse:")) parse = ApiConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parse == null) parse = Parse.get(0, result.getPlayUrl(), result.getHeader());
    }

    private void doInBackground(String key, String webUrl, String flag) {
        if (webUrl.startsWith("magnet:")) {
            onParseError();
            return;
        }
        switch (parse.getType()) {
            case 0: //嗅探
                App.post(() -> startWeb(key, parse.getUrl() + webUrl, parse.getHeaders(), callback));
                break;
            case 1: //Json
                jsonParse(webUrl);
                break;
            case 2: //Json 擴展
                jsonExtend(webUrl);
                break;
            case 3: //聚合
                jsonMix(webUrl, flag);
                break;
        }
    }

    private void jsonParse(String webUrl) {
        try {
            Response response = OkHttp.newCall(parse.getUrl() + webUrl, Headers.of(parse.getHeaders())).execute();
            JsonObject object = JsonParser.parseString(response.body().string()).getAsJsonObject();
            HashMap<String, String> headers = new HashMap<>();
            for (String key : object.keySet()) if (key.equalsIgnoreCase("user-agent") || key.equalsIgnoreCase("referer")) headers.put(key, object.get(key).getAsString());
            object = object.has("data") ? object.getAsJsonObject("data") : object;
            onParseSuccess(headers, Json.safeString(object, "url"), "");
        } catch (Exception e) {
            e.printStackTrace();
            onParseError();
        }
    }

    private void jsonExtend(String webUrl) {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (Parse item : ApiConfig.get().getParses()) if (item.getType() == 1) jxs.put(item.getName(), item.extUrl());
        checkResult(Result.fromObject(ApiConfig.get().jsonExt(parse.getUrl(), jxs, webUrl)));
    }

    private void jsonMix(String webUrl, String flag) {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        for (Parse item : ApiConfig.get().getParses()) jxs.put(item.getName(), item.mixMap());
        checkResult(Result.fromObject(ApiConfig.get().jsonExtMix(flag + "@", parse.getUrl(), parse.getName(), jxs, webUrl)));
    }

    private void checkResult(Result result) {
        if (result.getUrl().isEmpty()) {
            onParseError();
        } else if (result.getParse(0) == 1) {
            App.post(() -> startWeb(Utils.checkProxy(result.getUrl()), result.getHeaders(), callback));
        } else {
            onParseSuccess(result.getHeaders(), result.getUrl(), result.getJxFrom());
        }
    }

    private void startWeb(String url, Map<String, String> headers, Callback callback) {
        startWeb("", url, headers, callback);
    }

    private void startWeb(String key, String url, Map<String, String> headers, Callback callback) {
        webView = CustomWebView.create(App.get()).start(key, url, headers, callback);
    }

    private void onParseSuccess(Map<String, String> headers, String url, String from) {
        App.post(() -> {
            if (callback != null) callback.onParseSuccess(headers, url, from);
        });
    }

    private void onParseError() {
        App.post(() -> {
            if (callback != null) callback.onParseError();
        });
    }

    public void cancel() {
        if (executor != null) executor.shutdownNow();
        if (webView != null) webView.stop(false);
        executor = null;
        callback = null;
        webView = null;
    }

    public interface Callback {

        void onParseSuccess(Map<String, String> headers, String url, String from);

        void onParseError();
    }
}
package com.midairlogn.mlnetease;

import android.os.Handler;
import android.os.Looper;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class NeteaseApi {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .cookieJar(new CookieJar() {
                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {}

                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    return new ArrayList<>();
                }
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private SettingsManager settingsManager;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public NeteaseApi(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    public interface ApiCallback {
        void onSuccess(String result);
        void onError(String error);
    }

    private Request.Builder getDesktopBuilder(String url) {
        String musicU = settingsManager.getMusicU();
        StringBuilder cookieHeader = new StringBuilder("os=pc; appver=8.9.75; osver=; deviceId=mlncm!");
        if (!musicU.isEmpty()) {
            cookieHeader.append("; MUSIC_U=").append(musicU);
        }

        return new Request.Builder()
                .url(url)
                .addHeader("Cookie", cookieHeader.toString())
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/2.10.2.200154")
                .addHeader("Referer", "https://music.163.com/");
    }

    private Request.Builder getBrowserBuilder(String url) {
        String musicU = settingsManager.getMusicU();

        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0")
                .addHeader("Referer", "https://music.163.com/");

        if (!musicU.isEmpty()) {
            builder.addHeader("Cookie", "MUSIC_U=" + musicU + ";");
        }

        return builder;
    }

    public void search(String keyword, ApiCallback callback) {
        String limit = String.valueOf(settingsManager.getSearchLimit());
        FormBody body = new FormBody.Builder()
                .add("s", keyword)
                .add("type", "1")
                .add("limit", limit)
                .build();

        Request request = getBrowserBuilder("https://music.163.com/api/cloudsearch/pc")
                .post(body)
                .build();

        execute(request, callback);
    }

    public void songDetail(String ids, ApiCallback callback) {
        try {
            JSONArray jsonIds = new JSONArray();
            String[] idArray = ids.split(",");
            for (String id : idArray) {
                JSONObject obj = new JSONObject();
                String trimmedId = id.trim();
                try {
                    obj.put("id", Long.parseLong(trimmedId));
                } catch (NumberFormatException e) {
                    obj.put("id", trimmedId);
                }
                obj.put("v", 0);
                jsonIds.put(obj);
            }

            FormBody body = new FormBody.Builder()
                    .add("c", jsonIds.toString())
                    .build();

            // Use BrowserBuilder as per Python playlist_detail logic (although standalone songDetail might vary,
            // but in playlist context it uses browser headers)
            Request request = getBrowserBuilder("https://interface3.music.163.com/api/v3/song/detail")
                    .post(body)
                    .build();

            execute(request, callback);
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    public void songUrl(String id, ApiCallback callback) {
        String level = settingsManager.getQuality();
        try {
            String requestId = String.valueOf((long)(20000000 + Math.random() * 10000000));
            String headerJson = CryptoUtils.toHeaderJsonStr(requestId);
            String payloadJson = CryptoUtils.toPayloadJsonStr(id, level, headerJson);

            // Debug: log payload
            android.util.Log.d("NeteaseApi", "songUrl raw payload: " + payloadJson);

            String url = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1";
            String params = CryptoUtils.eapiEncrypt(url, payloadJson);

            FormBody body = new FormBody.Builder()
                    .add("params", params)
                    .build();

            Request request = getDesktopBuilder(url)
                    .post(body)
                    .header("Referer", "") // Match Python post() helper
                    .build();

            execute(request, callback);

        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    public void lyric(String id, ApiCallback callback) {
        FormBody body = new FormBody.Builder()
                .add("id", id)
                .add("cp", "false")
                .add("tv", "0")
                .add("lv", "0")
                .add("rv", "0")
                .add("kv", "0")
                .add("yv", "0")
                .add("ytv", "0")
                .add("yrv", "0")
                .build();

        // Python lyric_v1 uses raw requests.post (no Desktop headers).
        // Using BrowserBuilder (Mozilla) but removing Referer to be closer to "no referer".
        Request request = getBrowserBuilder("https://interface3.music.163.com/api/song/lyric")
                .post(body)
                .removeHeader("Referer")
                .build();

        execute(request, callback);
    }

    public void albumDetail(String id, ApiCallback callback) {
        new Thread(() -> {
            try {
                Request request = getBrowserBuilder("https://music.163.com/api/v1/album/" + id)
                        .get()
                        .build();
                Response response = client.newCall(request).execute();
                String body = response.body().string();
                JSONObject json = new JSONObject(body);

                JSONObject album = json.optJSONObject("album");
                if (album == null) {
                    postError(callback, "Album not found");
                    return;
                }

                JSONObject info = new JSONObject();
                info.put("id", album.opt("id"));
                info.put("name", album.optString("name"));
                info.put("coverImgUrl", CryptoUtils.getPicUrl(String.valueOf(album.opt("pic")), 300));
                info.put("artist", album.optJSONObject("artist") != null ? album.optJSONObject("artist").optString("name") : "");
                info.put("publishTime", album.optLong("publishTime"));
                info.put("description", album.optString("description"));

                JSONArray songsJson = new JSONArray();
                JSONArray songs = json.optJSONArray("songs");
                if (songs != null) {
                    for (int i = 0; i < songs.length(); i++) {
                        JSONObject song = songs.getJSONObject(i);
                        JSONObject songInfo = new JSONObject();
                        songInfo.put("id", song.opt("id"));
                        songInfo.put("name", song.optString("name"));

                        JSONArray ar = song.optJSONArray("ar");
                        StringBuilder artists = new StringBuilder();
                        if (ar != null) {
                            for (int k = 0; k < ar.length(); k++) {
                                if (k > 0) artists.append("/");
                                artists.append(ar.getJSONObject(k).optString("name"));
                            }
                        }
                        songInfo.put("artists", artists.toString());

                        JSONObject al = song.optJSONObject("al");
                        songInfo.put("album", al != null ? al.optString("name") : "");
                        songInfo.put("picUrl", al != null ? CryptoUtils.getPicUrl(String.valueOf(al.opt("pic")), 300) : "");

                        songsJson.put(songInfo);
                    }
                }
                info.put("songs", songsJson);

                JSONObject result = new JSONObject();
                result.put("album", info);
                result.put("status", 200);

                postSuccess(callback, result.toString());
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        }).start();
    }

    public void playlistDetail(String id, ApiCallback callback) {
        new Thread(() -> {
            try {
                // 1. Get Playlist Info
                Request req1 = getBrowserBuilder("https://music.163.com/api/v6/playlist/detail")
                        .post(new FormBody.Builder().add("id", id).build())
                        .build();

                Response res1 = client.newCall(req1).execute();
                String body1 = res1.body().string();
                JSONObject json1 = new JSONObject(body1);

                if (!json1.has("playlist")) {
                    postError(callback, "Playlist not found or error: " + body1);
                    return;
                }

                JSONObject playlist = json1.getJSONObject("playlist");
                JSONArray trackIds = playlist.getJSONArray("trackIds");

                List<String> allIds = new ArrayList<>();
                for (int i = 0; i < trackIds.length(); i++) {
                    allIds.add(String.valueOf(trackIds.getJSONObject(i).get("id")));
                }

                // 2. Batch fetch details
                JSONArray allSongs = new JSONArray();

                for (int i = 0; i < allIds.size(); i += 100) {
                    int end = Math.min(i + 100, allIds.size());
                    List<String> batch = allIds.subList(i, end);

                    JSONArray c = new JSONArray();
                    for (String tid : batch) {
                        JSONObject item = new JSONObject();
                        try {
                            item.put("id", Long.parseLong(tid)); // Use number to match Python logic
                        } catch (NumberFormatException e) {
                            item.put("id", tid);
                        }
                        item.put("v", 0);
                        c.put(item);
                    }

                    FormBody songBody = new FormBody.Builder()
                            .add("c", c.toString())
                            .build();

                    Request songReq = getBrowserBuilder("https://interface3.music.163.com/api/v3/song/detail")
                            .post(songBody)
                            .build();

                    Response songRes = client.newCall(songReq).execute();
                    JSONObject songJson = new JSONObject(songRes.body().string());

                    if (songJson.has("songs")) {
                        JSONArray songs = songJson.getJSONArray("songs");
                        for (int k = 0; k < songs.length(); k++) {
                            // Construct song info similar to Python search_music/playlist_detail
                            JSONObject song = songs.getJSONObject(k);
                            JSONObject songInfo = new JSONObject();
                            songInfo.put("id", song.opt("id"));
                            songInfo.put("name", song.optString("name"));

                            JSONArray ar = song.optJSONArray("ar");
                            StringBuilder artists = new StringBuilder();
                            if (ar != null) {
                                for (int m = 0; m < ar.length(); m++) {
                                    if (m > 0) artists.append("/");
                                    artists.append(ar.getJSONObject(m).optString("name"));
                                }
                            }
                            songInfo.put("artists", artists.toString());

                            JSONObject al = song.optJSONObject("al");
                            songInfo.put("album", al != null ? al.optString("name") : "");
                            songInfo.put("picUrl", al != null ? al.optString("picUrl") : "");

                            allSongs.put(songInfo);
                        }
                    }
                }

                JSONObject result = new JSONObject();
                result.put("playlist", playlist); // Keep original playlist info
                result.put("songs", allSongs); // Add processed songs
                result.put("status", 200);

                postSuccess(callback, result.toString());

            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        }).start();
    }

    public void getSongFullInfo(String id, ApiCallback callback) {
        new Thread(() -> {
            try {
                // 1. Get Song URL
                String level = settingsManager.getQuality();

                String requestId = String.valueOf((long)(20000000 + Math.random() * 10000000));
                String headerJson = CryptoUtils.toHeaderJsonStr(requestId);
                String payloadJson = CryptoUtils.toPayloadJsonStr(id, level, headerJson);

                String url = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1";
                String params = CryptoUtils.eapiEncrypt(url, payloadJson);

                android.util.Log.d("NeteaseApi", "songUrl payload: " + payloadJson);

                FormBody bodyUrl = new FormBody.Builder().add("params", params).build();
                // Override Referer for songUrl as per Python 'post' function (Referer='')
                Request reqUrl = getDesktopBuilder(url).post(bodyUrl).header("Referer", "").build();
                Response resUrl = client.newCall(reqUrl).execute();
                String resUrlStr = resUrl.body().string();
                android.util.Log.d("NeteaseApi", "songUrl response: " + resUrlStr);

                JSONObject jsonUrl = new JSONObject(resUrlStr);

                // 2. Get Song Detail (Name, Pic, etc)
                JSONArray jsonIds = new JSONArray();
                JSONObject objId = new JSONObject();
                try {
                    objId.put("id", Long.parseLong(id));
                } catch (NumberFormatException e) {
                    objId.put("id", id);
                }
                objId.put("v", 0);
                jsonIds.put(objId);
                FormBody bodyDetail = new FormBody.Builder().add("c", jsonIds.toString()).build();
                // Use BrowserBuilder for song/detail here to match playlistDetail logic?
                // Wait, Python uses `name_v1` which uses basic `requests.post`.
                // But let's stick to BrowserBuilder for consistency with playlistDetail
                Request reqDetail = getBrowserBuilder("https://interface3.music.163.com/api/v3/song/detail").post(bodyDetail).build();
                Response resDetail = client.newCall(reqDetail).execute();
                JSONObject jsonDetail = new JSONObject(resDetail.body().string());

                // 3. Get Lyrics
                FormBody bodyLyric = new FormBody.Builder()
                        .add("id", id)
                        .add("cp", "false")
                        .add("tv", "0")
                        .add("lv", "0")
                        .add("rv", "0")
                        .add("kv", "0")
                        .add("yv", "0")
                        .add("ytv", "0")
                        .add("yrv", "0")
                        .build();
                Request reqLyric = getBrowserBuilder("https://interface3.music.163.com/api/song/lyric")
                        .post(bodyLyric)
                        .removeHeader("Referer")
                        .build();
                Response resLyric = client.newCall(reqLyric).execute();
                JSONObject jsonLyric = new JSONObject(resLyric.body().string());

                // Combine results
                JSONObject result = new JSONObject();

                // Process URL
                if (jsonUrl.has("data") && jsonUrl.getJSONArray("data").length() > 0) {
                    JSONObject dataObj = jsonUrl.getJSONArray("data").getJSONObject(0);
                    result.put("url", dataObj.optString("url", ""));
                    result.put("size", dataObj.optLong("size", 0));
                    result.put("level", dataObj.optString("level", ""));
                }

                // Process Detail
                if (jsonDetail.has("songs") && jsonDetail.getJSONArray("songs").length() > 0) {
                    JSONObject songObj = jsonDetail.getJSONArray("songs").getJSONObject(0);
                    result.put("name", songObj.optString("name", ""));

                    JSONObject al = songObj.optJSONObject("al");
                    if (al != null) {
                        result.put("al_name", al.optString("name", ""));
                        result.put("pic", al.optString("picUrl", ""));
                    }

                    JSONArray ar = songObj.optJSONArray("ar");
                    if (ar != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < ar.length(); k++) {
                            if (k > 0) sb.append("/");
                            sb.append(ar.getJSONObject(k).optString("name"));
                        }
                        result.put("ar_name", sb.toString());
                    }
                }

                // Process Lyric
                if (jsonLyric.has("lrc")) {
                    result.put("lyric", jsonLyric.getJSONObject("lrc").optString("lyric", ""));
                }
                if (jsonLyric.has("tlyric")) {
                    result.put("tlyric", jsonLyric.getJSONObject("tlyric").optString("lyric", ""));
                }

                result.put("id", id);
                result.put("status", 200);

                postSuccess(callback, result.toString());

            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        }).start();
    }

    private void execute(Request request, ApiCallback callback) {
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    postSuccess(callback, response.body().string());
                } else {
                    postError(callback, "HTTP Error: " + response.code());
                }
            }
        });
    }

    private void postSuccess(ApiCallback callback, String result) {
        android.util.Log.d("NeteaseApi", "Response Success Body: " + result);
        mainHandler.post(() -> callback.onSuccess(result));
    }

    private void postError(ApiCallback callback, String error) {
        mainHandler.post(() -> callback.onError(error));
    }
}

package com.example.imageblog;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Spanned;
import android.content.Intent;
import android.view.inputmethod.EditorInfo;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.text.HtmlCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivate extends AppCompatActivity {
    private static final String TAG = "MainActivate";
    public static final int REQ_VIEW_POST = 1001; // 상세 보기/편집 요청 코드
    TextView textView;
    RecyclerView recyclerView;
    MaterialButton btnLoad;
    MaterialButton btnSave;
    EditText etSearch; // 추가: 검색 입력
    ImageButton btnSearch; // 추가: 검색 버튼
    String site_url = "https://cwijiq.pythonanywhere.com"; // 변경된 API 호스트
    Thread fetchThread;
    String lastRawJson = null; // 디버깅용으로 원시 JSON을 저장
    //PutPost taskUpload;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activate_main); // 레이아웃 이름 수정

        // Toolbar를 레이아웃에서 찾아서 지원 액션바로 설정
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                // 기본 타이틀은 숨기고 커스텀 TextView로 대체
                getSupportActionBar().setDisplayShowTitleEnabled(false);
            }
        }

        textView = findViewById(R.id.textView);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 백그라운드에서 토큰을 미리 가져와서 첫 요청 시 인터셉터가 블로킹하는 시간을 줄입니다.
        new Thread(() -> {
            try {
                AuthHelper.getToken(MainActivate.this);
            } catch (Exception e) {
                Log.d(TAG, "pre-warm token failed", e);
            }
        }).start();

        // 버튼 참조 (XML의 onClick은 그대로 사용)
        btnLoad = findViewById(R.id.btn_load);
        btnSave = findViewById(R.id.btn_save);

        // 검색 뷰 초기화
        etSearch = findViewById(R.id.etSearch);
        btnSearch = findViewById(R.id.btn_search);
        btnSearch.setOnClickListener(v -> performSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch();
                return true;
            }
            return false;
        });

        // ...changed: 초기에는 게시글을 즉시 불러오지 않고, RecyclerView를 숨깁니다...
        recyclerView.setVisibility(View.GONE);
        textView.setText("동기화 버튼을 눌러 게시글을 불러오세요.");

        Log.d(TAG, "onCreate: 초기 상태, 자동 로드 없이 대기합니다.");
        // 자동 로드 제거: startFetch 호출 없음
    }

    public void onClickDownload(View v) {
// 수동으로 버튼 눌렀을 때 재요청
        Log.d(TAG, "onClickDownload: 버튼 눌림, 데이터 로드 시작");
        if (fetchThread != null && fetchThread.isAlive()) {
            fetchThread.interrupt();
        }
        // 로딩 시작 시 기존 목록 숨기고 상태 표시
        recyclerView.setVisibility(View.GONE);
        textView.setText("로딩 중...");
        // 중복 요청 방지: 버튼 비활성화
        if (btnLoad != null) {
            btnLoad.setEnabled(false);
            btnLoad.setAlpha(0.6f);
        }
        startFetch(site_url + "/api/posts"); // 사용자 제공 엔드포인트 사용
        Toast.makeText(getApplicationContext(), "Download", Toast.LENGTH_LONG).show();
    }
    public void onClickUpload(View v) {
        // NewPostActivity를 열어 사용자가 이미지/제목/본문을 입력하고 업로드하도록 함
        Intent intent = new Intent(this, NewPostActivity.class);
        // startActivityForResult로 열어 업로드 성공 시 리프레시를 받도록 함
        startActivityForResult(intent, REQ_VIEW_POST);
    }

    // startFetch: 백그라운드 스레드에서 API 호출 및 파싱 수행
    private void startFetch(final String apiUrl) {
        // 이전에 저장된 rawJson을 초기화해서 UI에 이전 결과가 표시되는 것을 방지
        lastRawJson = null;
        fetchThread = new Thread(() -> {
            List<Post> postList = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            // 안전: 스레드 시작 시에도 lastRawJson이 비어있도록 초기화
            lastRawJson = null;

            OkHttpClient client = NetworkClient.getClient(MainActivate.this);
            Request req = new Request.Builder().url(apiUrl).get().build();

            try (Response resp = client.newCall(req).execute()) {
                int responseCode = resp.code();
                Log.d(TAG, "startFetch: responseCode=" + responseCode);

                if (responseCode == 200) {
                    String strJson = resp.body() != null ? resp.body().string() : "";
                    lastRawJson = strJson;
                    Log.d(TAG, "startFetch: raw json=" + strJson);

                    JSONArray aryJson = null;
                    try {
                        aryJson = new JSONArray(strJson);
                    } catch (JSONException ex) {
                        // not an array; try object forms
                        try {
                            JSONObject root = new JSONObject(strJson);
                            if (root.has("results") && root.opt("results") instanceof JSONArray) {
                                aryJson = root.getJSONArray("results");
                            } else if (root.has("data") && root.opt("data") instanceof JSONArray) {
                                aryJson = root.getJSONArray("data");
                            } else if (root.has("id")) {
                                // single object -> create one Post
                                int id = root.optInt("id", -1);
                                String author = root.optString("author", "");
                                String title = root.optString("title", "");
                                String text = root.optString("text", root.optString("body", ""));
                                String published = root.optString("published_date", root.optString("published", ""));
                                String img = root.optString("image", "");
                                if (img.isEmpty()) img = root.optString("image_url", "");
                                if (img.isEmpty()) img = root.optString("photo", "");
                                String resolved = img.isEmpty() ? "" : resolveUrl(img);
                                postList.add(new Post(id, author, title, text, published, resolved));
                            } else {
                                // try to find any array inside object
                                Iterator<String> keys = root.keys();
                                while (keys.hasNext()) {
                                    String k = keys.next();
                                    Object v = root.opt(k);
                                    if (v instanceof JSONArray) {
                                        aryJson = (JSONArray) v;
                                        break;
                                    }
                                }
                            }
                        } catch (JSONException je) {
                            Log.w(TAG, "startFetch: JSON 파싱 실패", je);
                        }
                    }

                    if (aryJson != null) {
                        for (int i = 0; i < aryJson.length(); i++) {
                            if (Thread.currentThread().isInterrupted()) return;
                            try {
                                JSONObject obj = aryJson.getJSONObject(i);
                                int id = obj.optInt("id", -1);
                                String author = obj.optString("author", "");
                                String title = obj.optString("title", "");
                                String text = obj.optString("text", obj.optString("body", ""));
                                String published = obj.optString("published_date", obj.optString("published", ""));
                                String img = obj.optString("image", "");
                                if (img.isEmpty()) img = obj.optString("image_url", "");
                                if (img.isEmpty()) img = obj.optString("photo", "");
                                String resolved = img.isEmpty() ? "" : resolveUrl(img);
                                if (!resolved.isEmpty()) seen.add(resolved);
                                postList.add(new Post(id, author, title, text, published, resolved));
                            } catch (JSONException je) {
                                Log.w(TAG, "startFetch: 배열 요소 파싱 실패", je);
                            }
                        }
                    }

                    // fallback: try to extract an image URL from raw JSON if nothing parsed
                    if (postList.isEmpty() && lastRawJson != null) {
                        Pattern p = Pattern.compile("https?://[^\"'\\s,<>]+", Pattern.CASE_INSENSITIVE);
                        Matcher m = p.matcher(lastRawJson);
                        if (m.find()) {
                            String found = m.group();
                            if (!seen.contains(found)) {
                                Post p0 = new Post(-1, "", "", "", "", found);
                                postList.add(p0);
                            }
                        }
                    }

                } else {
                    Log.w(TAG, "startFetch: HTTP 응답 코드가 OK가 아님: " + responseCode);
                }
            } catch (IOException e) {
                Log.e(TAG, "startFetch: 예외 발생", e);
            }

            // UI 업데이트
            final List<Post> finalPosts = postList;
            runOnUiThread(() -> onPostsFetched(finalPosts));
        });

        fetchThread.start();
    }

    private String resolveUrl(String image) {
        String resolved = image;
        if (!image.startsWith("http")) {
            if (image.startsWith("/")) {
                resolved = site_url + image;
            } else {
                resolved = site_url + "/" + image;
            }
        }
        return resolved;
    }

    private void onPostsFetched(List<Post> posts) {
        Log.d(TAG, "onPostsFetched: posts size=" + (posts == null ? 0 : posts.size()));
        if (posts == null || posts.isEmpty()) {
            // 게시글이 없을 땐 리스트 숨김, 사용자에게 메시지를 표시하지 않음(빈 상태 유지)
            recyclerView.setVisibility(View.GONE);
            textView.setText("");
            // 디버그용으로 rawJson은 로그에 남김
            if (lastRawJson != null && !lastRawJson.isEmpty()) {
                Log.d(TAG, "rawJson when empty: " + (lastRawJson.length() > 1000 ? lastRawJson.substring(0, 1000) + "..." : lastRawJson));
            }
             // 동기화 완료/실패 후 버튼 다시 활성화
             if (btnLoad != null) {
                 btnLoad.setEnabled(true);
                 btnLoad.setAlpha(1f);
             }
             if (btnSearch != null) {
                 btnSearch.setEnabled(true);
                 btnSearch.setAlpha(1f);
             }
            Log.d(TAG, "onPostsFetched: 게시글 없음");
        } else {
            String html = "이미지 로드 성공! &nbsp;&nbsp;&nbsp; 총 글 개수: <b><font color='#FF424242'>" + posts.size() + "개</font></b>";
            Spanned sp = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY);
            textView.setText(sp, TextView.BufferType.SPANNABLE);
            // 게시글이 있을 땐 리스트 보이고 어댑터 적용
            recyclerView.setVisibility(View.VISIBLE);
            ImageAdapter adapter = new ImageAdapter(posts);
            recyclerView.setAdapter(adapter);
            // 동기화 완료 후 버튼 다시 활성화
            if (btnLoad != null) {
                btnLoad.setEnabled(true);
                btnLoad.setAlpha(1f);
            }
            if (btnSearch != null) {
                btnSearch.setEnabled(true);
                btnSearch.setAlpha(1f);
            }
            Log.d(TAG, "onPostsFetched: RecyclerView에 adapter 적용 완료");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VIEW_POST && resultCode == RESULT_OK) {
            Log.d(TAG, "onActivityResult: refreshing posts after detail activity result");
            if (fetchThread != null && fetchThread.isAlive()) {
                fetchThread.interrupt();
            }
            // UI 상태: 숨기고 로딩 표시
            recyclerView.setVisibility(View.GONE);
            textView.setText("로딩 중...");
            if (btnLoad != null) {
                btnLoad.setEnabled(false);
                btnLoad.setAlpha(0.6f);
            }
            startFetch(site_url + "/api/posts");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 토큰이 서버에서 무효화되었다고 표시된 경우 사용자에게 알림
        try {
            if (AuthHelper.isTokenInvalid(this)) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("인증 오류")
                        .setMessage("토큰이 만료되었거나 유효하지 않습니다. 다시 시도하려면 확인을 누르세요.")
                        .setPositiveButton("확인", (d, w) -> {
                            AuthHelper.clearTokenInvalid(this);
                        })
                        .setCancelable(false)
                        .show();
            }
        } catch (Exception e) {
            Log.w(TAG, "onResume: token invalid check failed", e);
        }
    }

    // 검색 실행: etSearch의 텍스트로 search API 호출
    private void performSearch() {
        String q = etSearch == null ? "" : etSearch.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "검색어를 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String enc = java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8.name());
            String searchUrl = site_url + "/api/posts/search/?q=" + enc;
            Log.d(TAG, "performSearch: url=" + searchUrl);
            if (fetchThread != null && fetchThread.isAlive()) {
                fetchThread.interrupt();
            }
            // UI 상태
            recyclerView.setVisibility(View.GONE);
            textView.setText("검색 중...");
            if (btnLoad != null) {
                btnLoad.setEnabled(false);
                btnLoad.setAlpha(0.6f);
            }
            if (btnSearch != null) {
                btnSearch.setEnabled(false);
                btnSearch.setAlpha(0.6f);
            }
            startFetch(searchUrl);
        } catch (Exception e) {
            Log.w(TAG, "performSearch: encoding failed", e);
            Toast.makeText(this, "검색어 인코딩 오류", Toast.LENGTH_SHORT).show();
        }
    }
}

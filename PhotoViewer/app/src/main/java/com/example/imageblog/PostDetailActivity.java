package com.example.imageblog;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import android.widget.Toast; // 추가: Toast import

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PostDetailActivity extends AppCompatActivity {
    private static final String TAG = "PostDetailActivity";
    private static final int REQ_EDIT_POST = 2001; // 추가: 편집 요청 코드
    private int postId = -1; // 게시글 id 저장

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_post_detail);

        TextView headerTitle = findViewById(R.id.headerTitle);
        ImageButton btnClose = findViewById(R.id.btnClose);
        TextView bodyView = findViewById(R.id.detailBody);
        TextView dateView = findViewById(R.id.detailDate);
        ImageView imageView = findViewById(R.id.detailImage);
        TextView labelBody = findViewById(R.id.labelBody);

        btnClose.setOnClickListener(v -> finish());

        Intent intent = getIntent();
        if (intent != null) {
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");
            String published = intent.getStringExtra("published");
            String image = intent.getStringExtra("image");
            postId = intent.getIntExtra("id", -1);

            Log.d(TAG, "open detail for id=" + postId + ", title=" + title);

            headerTitle.setText(title == null ? "" : title);
            bodyView.setText(text == null ? "" : text);

            // 날짜를 한국어 포맷으로 변환
            if (published != null && !published.isEmpty()) {
                dateView.setText(formatDateString(published));
            } else {
                dateView.setText("");
            }

            // 이미지 로딩 - 둥근 모서리 적용
            if (image != null && !image.isEmpty()) {
                int radiusDp = 12;
                int radiusPx = (int) (radiusDp * getResources().getDisplayMetrics().density + 0.5f);
                Glide.with(this)
                        .load(image)
                        .apply(RequestOptions.bitmapTransform(new RoundedCorners(radiusPx))
                                .placeholder(android.R.drawable.ic_menu_report_image))
                        .into(imageView);
            } else {
                imageView.setImageDrawable(null);
            }

            // 본문이 비어있으면 레이블 숨기기
            if (text == null || text.trim().isEmpty()) {
                labelBody.setVisibility(View.GONE);
                bodyView.setVisibility(View.GONE);
            } else {
                labelBody.setVisibility(View.VISIBLE);
                bodyView.setVisibility(View.VISIBLE);
            }

            // 추가: 수정/삭제 버튼 처리
            android.widget.Button btnEdit = findViewById(R.id.btnEditPost);
            android.widget.Button btnDelete = findViewById(R.id.btnDeletePost);

            btnEdit.setOnClickListener(v -> {
                // 편집 화면으로 이동: 기존 데이터를 전달
                android.content.Intent editIntent = new android.content.Intent(this, NewPostActivity.class);
                editIntent.putExtra("id", postId);
                editIntent.putExtra("title", title == null ? "" : title);
                editIntent.putExtra("text", text == null ? "" : text);
                editIntent.putExtra("image", image == null ? "" : image);
                // startActivityForResult로 열어 편집 완료 시 결과를 받을 수 있게 함
                startActivityForResult(editIntent, REQ_EDIT_POST);
            });

            btnDelete.setOnClickListener(v -> {
                // 삭제 확인 다이얼로그
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("게시글 삭제")
                        .setMessage("정말 이 게시글을 삭제하시겠습니까?")
                        .setNegativeButton("취소", (d, which) -> d.dismiss())
                        .setPositiveButton("삭제", (d, which) -> {
                            // DELETE 호출
                            performDeletePost(postId);
                        })
                        .show();
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT_POST) {
            if (resultCode == RESULT_OK) {
                // 편집이 성공적으로 완료되었음을 상위(MainActivate)로 전달하기 위해 RESULT_OK로 종료
                setResult(RESULT_OK);
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            if (AuthHelper.isTokenInvalid(this)) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("인증 오류")
                        .setMessage("토큰이 만료되었습니다.")
                        .setPositiveButton("확인", (d, w) -> AuthHelper.clearTokenInvalid(this))
                        .setCancelable(false)
                        .show();
            }
        } catch (Exception e) {
            Log.w(TAG, "onResume: token invalid check failed", e);
        }
    }

    private void performDeletePost(int id) {
        if (id < 0) {
            Toast.makeText(this, "유효하지 않은 게시글입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String deleteUrl = "https://cwijiq.pythonanywhere.com/api_root/Post/" + id + "/";
        Toast.makeText(this, "삭제 요청중...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                okhttp3.OkHttpClient client = NetworkClient.getClient(PostDetailActivity.this);
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(deleteUrl)
                        .delete()
                        .build();
                okhttp3.Response resp = client.newCall(req).execute();
                int code = resp.code();
                Log.d(TAG, "DELETE response code=" + code);
                String body = resp.body() != null ? resp.body().string() : null;
                resp.close();

                runOnUiThread(() -> {
                    if (code == 204 || code == 200) {
                        Toast.makeText(PostDetailActivity.this, "삭제되었습니다.", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    } else {
                        String msg = "삭제 실패: HTTP " + code + (body != null && !body.isEmpty() ? (" - " + body) : "");
                        Toast.makeText(PostDetailActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "performDeletePost failed", e);
                runOnUiThread(() -> Toast.makeText(PostDetailActivity.this, "삭제 중 오류가 발생했습니다: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
         }).start();
    }

    // 날짜 문자열을 "2025년 10월 9일 9:31 오전" 형식으로 변환
    private String formatDateString(String rawDate) {
        if (rawDate == null || rawDate.isEmpty()) return "";
        String trimmed = rawDate;
        try {
            if (trimmed.length() > 19 && trimmed.charAt(19) != ' ') {
                trimmed = trimmed.substring(0, 19);
            }
        } catch (Exception e) {
            // ignore
        }
        String patternIn = trimmed.contains("T") ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd HH:mm:ss";
        SimpleDateFormat inputFormat = new SimpleDateFormat(patternIn, Locale.getDefault());
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy년 M월 d일 h:mm a", Locale.KOREAN);

        try {
            Date date = inputFormat.parse(trimmed);
            if (date != null) {
                return outputFormat.format(date);
            } else {
                return rawDate;
            }
        } catch (ParseException e) {
            Log.w(TAG, "formatDateString: failed to parse date='" + rawDate + "'", e);
            return rawDate;
        }
    }
}

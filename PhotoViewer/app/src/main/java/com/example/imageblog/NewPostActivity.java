package com.example.imageblog;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NewPostActivity extends AppCompatActivity {
    private static final String TAG = "NewPostActivity";
    private ImageView imagePreview;
    private EditText etTitle, etText;
    private ProgressBar progressBar;
    private Uri imageUri;
    private TextView newPostInfo;
    private int postId = -1; // 편집 시 사용

    private ActivityResultLauncher<String> pickImageLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_post);

        imagePreview = findViewById(R.id.imagePreview);
        etTitle = findViewById(R.id.etTitle);
        etText = findViewById(R.id.etText);
        Button btnPick = findViewById(R.id.btnPickImage);
        Button btnSubmit = findViewById(R.id.btnSubmit);
        progressBar = findViewById(R.id.progressBar);
        newPostInfo = findViewById(R.id.newPostInfo);

        ImageButton btnClose = findViewById(R.id.newPostClose);
        btnClose.setOnClickListener(v -> finish());

        // 인텐트에서 편집용 데이터가 있을 경우 필드 채우기
        Intent intent = getIntent();
        if (intent != null) {
            postId = intent.getIntExtra("id", -1);
            String title = intent.getStringExtra("title");
            String text = intent.getStringExtra("text");
            String image = intent.getStringExtra("image");
            if (title != null) etTitle.setText(title);
            if (text != null) etText.setText(text);
            if (image != null && !image.isEmpty()) {
                // image가 URL일 경우 Glide로 로드
                try {
                    int radiusDp = 12;
                    int radiusPx = (int) (radiusDp * getResources().getDisplayMetrics().density + 0.5f);
                    Glide.with(this)
                            .load(image)
                            .apply(RequestOptions.bitmapTransform(new RoundedCorners(radiusPx)))
                            .into(imagePreview);
                    newPostInfo.setText("기존 이미지");
                } catch (Exception e) {
                    Log.w(TAG, "failed to load provided image", e);
                }
            }
        }

        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                imageUri = uri;
                // Glide로 이미지 로드하고 둥근 모서리 적용
                int radiusDp = 12;
                int radiusPx = (int) (radiusDp * getResources().getDisplayMetrics().density + 0.5f);
                Glide.with(this)
                        .load(uri)
                        .apply(RequestOptions.bitmapTransform(new RoundedCorners(radiusPx)))
                        .into(imagePreview);

                // 파일 이름 표시
                String name = getDisplayName(uri);
                newPostInfo.setText(Objects.requireNonNullElse(name, ""));
            }
        });

        btnPick.setOnClickListener(v -> pickImageLauncher.launch("image/*"));

        btnSubmit.setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            String text = etText.getText().toString().trim();
            if (title.isEmpty() && text.isEmpty() && imageUri == null && postId < 0) {
                Toast.makeText(this, "제목/본문/이미지 중 하나 이상 입력하세요", Toast.LENGTH_SHORT).show();
                return;
            }
            uploadPost(title, text, imageUri, btnSubmit);
        });
    }

    private String getDisplayName(Uri uri) {
        String displayName = null;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx != -1 && cursor.moveToFirst()) {
                    displayName = cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getDisplayName failed", e);
        }
        return displayName;
    }

    private void uploadPost(String title, String text, Uri imageUri, Button btnSubmit) {
        progressBar.setVisibility(android.view.View.VISIBLE);
        btnSubmit.setEnabled(false);

        new Thread(() -> {
            OkHttpClient client = NetworkClient.getClient(NewPostActivity.this);
            boolean isEdit = postId >= 0;
            String url;
            if (isEdit) {
                // Use cwijiq host for PATCH as requested
                url = "https://cwijiq.pythonanywhere.com/api_root/Post/" + postId + "/";
            } else {
                url = "https://cwijiq.pythonanywhere.com/api_root/Post/";
            }

            try {
                String token = AuthHelper.getToken(NewPostActivity.this);

                if (isEdit) {
                    // Build JSON with only non-empty fields
                    org.json.JSONObject json = new org.json.JSONObject();
                    try {
                        if (title != null && !title.isEmpty()) json.put("title", title);
                        if (text != null && !text.isEmpty()) json.put("text", text);
                    } catch (org.json.JSONException je) {
                        Log.w(TAG, "failed to build json for patch", je);
                    }

                    if (json.length() == 0) {
                        // nothing to update
                        runOnUiThread(() -> {
                            progressBar.setVisibility(android.view.View.GONE);
                            btnSubmit.setEnabled(true);
                            Toast.makeText(NewPostActivity.this, "수정할 내용이 없습니다.", Toast.LENGTH_SHORT).show();
                        });
                        return;
                    }

                    // If an image was selected during edit, inform user that PATCH does not update image here
                    if (imageUri != null) {
                        runOnUiThread(() -> Toast.makeText(NewPostActivity.this, "편집 시 이미지 변경은 현재 지원되지 않습니다. 이미지 변경을 원하면 새로 업로드하세요.", Toast.LENGTH_LONG).show());
                    }

                    String jsonStr = json.toString();
                    RequestBody requestBody = RequestBody.create(jsonStr, okhttp3.MediaType.parse("application/json; charset=utf-8"));
                    Request.Builder reqBuilder = new Request.Builder()
                            .url(url)
                            .patch(requestBody);
                     Request request = reqBuilder.build();

                     Response response = client.newCall(request).execute();
                     final boolean success = response.isSuccessful();
                     final String respBody = response.body() != null ? response.body().string() : "";
                     response.close();

                     runOnUiThread(() -> {
                         progressBar.setVisibility(android.view.View.GONE);
                         btnSubmit.setEnabled(true);
                         if (success) {
                             Toast.makeText(NewPostActivity.this, "수정 성공", Toast.LENGTH_SHORT).show();
                             setResult(RESULT_OK);
                             finish();
                         } else {
                             Toast.makeText(NewPostActivity.this, "수정 실패: " + respBody, Toast.LENGTH_LONG).show();
                         }
                     });

                } else {
                    MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("author", "1")
                            .addFormDataPart("title", title)
                            .addFormDataPart("text", text);

                    if (imageUri != null) {
                        try (InputStream is = getContentResolver().openInputStream(imageUri)) {
                            if (is != null) {
                                byte[] data = toByteArray(is);

                                String mime = getContentResolver().getType(imageUri);
                                if (mime == null) mime = "application/octet-stream";
                                MediaType mediaType = MediaType.parse(mime);

                                String filename = "upload_image";
                                try (android.database.Cursor cursor = getContentResolver().query(imageUri, null, null, null, null)) {
                                    if (cursor != null) {
                                        int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                                        if (nameIndex != -1 && cursor.moveToFirst()) {
                                            filename = cursor.getString(nameIndex);
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "filename lookup failed", e);
                                }

                                RequestBody fileBody = RequestBody.create(data, mediaType);
                                builder.addFormDataPart("image", filename, fileBody);
                            } else {
                                Log.w(TAG, "InputStream is null for imageUri");
                            }
                        }
                    }

                    RequestBody requestBody = builder.build();
                    Request.Builder reqBuilder = new Request.Builder().url(url);
                    reqBuilder.post(requestBody);

                    Request request = reqBuilder.build();

                    Response response = client.newCall(request).execute();
                    final boolean success = response.isSuccessful();
                    final String respBody = response.body() != null ? response.body().string() : "";
                    response.close();

                    runOnUiThread(() -> {
                        progressBar.setVisibility(android.view.View.GONE);
                        btnSubmit.setEnabled(true);
                        if (success) {
                            Toast.makeText(NewPostActivity.this, "게시 성공", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            Toast.makeText(NewPostActivity.this, "게시 실패: " + respBody, Toast.LENGTH_LONG).show();
                        }
                    });
                }

            } catch (IOException e) {
                Log.e(TAG, "upload failed", e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(android.view.View.GONE);
                    btnSubmit.setEnabled(true);
                    Toast.makeText(NewPostActivity.this, "업로드 중 오류 발생: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static byte[] toByteArray(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = input.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
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
            android.util.Log.w(TAG, "onResume: token invalid check failed", e);
        }
    }
}

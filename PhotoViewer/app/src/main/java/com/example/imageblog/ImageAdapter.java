package com.example.imageblog;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {
    private final List<Post> posts;
    private static final String TAG = "ImageAdapter";

    public ImageAdapter(List<Post> posts) {
        this.posts = posts;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_image, parent, false);
        return new ImageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        Post post = posts.get(position);

        // 제목과 내용
        holder.textTitle.setText(post.getTitle() == null ? "" : post.getTitle());
        holder.textBody.setText(post.getText() == null ? "" : post.getText());

        // ✅ 발행 시간 한국어 형식으로 표시
        String rawDate = post.getPublishedDate(); // 예: "2025-10-09T21:31:00"
        if (rawDate != null && !rawDate.isEmpty()) {
            String formattedDate = formatDateString(rawDate);
            holder.textDate.setText(formattedDate);
        } else {
            holder.textDate.setText("");
        }

        // 이미지 표시
        String url = post.getImageUrl();
        if (url != null && !url.isEmpty()) {
            Glide.with(holder.imageViewItem.getContext())
                    .load(url)
                    .centerCrop()
                    .into(holder.imageViewItem);
        } else {
            holder.imageViewItem.setImageDrawable(null);
        }

        // 아이템 클릭 시 상세 화면으로 이동
        holder.itemView.setOnClickListener(v -> {
            android.content.Context ctx = v.getContext();
            android.content.Intent intent = new android.content.Intent(ctx, PostDetailActivity.class);
            intent.putExtra("title", post.getTitle() == null ? "" : post.getTitle());
            intent.putExtra("text", post.getText() == null ? "" : post.getText());
            intent.putExtra("published", post.getPublishedDate() == null ? "" : post.getPublishedDate());
            intent.putExtra("image", post.getImageUrl() == null ? "" : post.getImageUrl());
            intent.putExtra("id", post.getId());
            // Activity 컨텍스트이면 startActivityForResult로 열어 삭제/수정 후 결과를 받을 수 있게 함
            if (ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).startActivityForResult(intent, MainActivate.REQ_VIEW_POST);
            } else {
                ctx.startActivity(intent);
            }
        });
    }

    @Override
    public int getItemCount() {
        return posts == null ? 0 : posts.size();
    }

    // 날짜 문자열을 "2025년 10월 9일 9:31 오후" 형식으로 변환
    private String formatDateString(String rawDate) {
        // rawDate에 timezone(+09:00 또는 Z) 정보가 붙어있을 수 있으므로 초(second)까지의 부분만 파싱 시도
        if (rawDate == null || rawDate.isEmpty()) return "";
        String trimmed = rawDate;
        try {
            // ISO 8601 형식 예: 2025-10-29T22:23:00+09:00 또는 2025-10-29T22:23:00Z
            // 초(second)까지의 길이는 19자 (yyyy-MM-dd'T'HH:mm:ss)
            if (trimmed.length() > 19 && trimmed.charAt(19) != ' ') {
                trimmed = trimmed.substring(0, 19);
            }
        } catch (Exception e) {
            // 실패해도 원본을 사용하여 파싱 시도
        }

        SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault());
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy년 M월 d일 h:mm a", Locale.KOREAN);

        try {
            Date date = inputFormat.parse(trimmed);
            if (date == null) {
                Log.w(TAG, "formatDateString: parsed date is null for rawDate='" + rawDate + "'");
                return rawDate;
            }
            return outputFormat.format(date); // 예: "2025년 10월 9일 9:31 오후"
        } catch (ParseException e) {
            Log.w(TAG, "formatDateString: failed to parse date='" + rawDate + "'", e);
            return rawDate; // 파싱 실패 시 원본 문자열 표시
        }
    }

    public static class ImageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageViewItem;
        TextView textTitle;
        TextView textDate;
        TextView textBody;

        public ImageViewHolder(View itemView) {
            super(itemView);
            imageViewItem = itemView.findViewById(R.id.imageViewItem);
            textTitle = itemView.findViewById(R.id.textTitle);
            textDate = itemView.findViewById(R.id.textDate);
            textBody = itemView.findViewById(R.id.textBody);
        }
    }
}

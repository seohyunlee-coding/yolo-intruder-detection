from django.shortcuts import render, get_object_or_404, redirect
from .models import Post
from django.utils import timezone

# added
from rest_framework import viewsets
from rest_framework import generics
from .serializers import PostSerializer
from django.db.models import Q


class blogImage(viewsets.ModelViewSet):
    queryset = Post.objects.all()
    serializer_class = PostSerializer


class PostList(generics.ListAPIView):
    """API: 게시물 리스트를 JSON으로 반환합니다."""
    queryset = Post.objects.all()
    serializer_class = PostSerializer


class PostSearch(generics.ListAPIView):
    """검색 API: 쿼리파라미터 'q'로 제목/본문을 대소문자 구분없이 검색합니다.

    사용 예: GET /api/posts/search/?q=django
    결과는 published_date가 현재 시각보다 이전인(게시된) 게시물만 반환합니다.
    """
    serializer_class = PostSerializer

    def get_queryset(self):
        q = self.request.query_params.get('q', '')
        qs = Post.objects.filter(published_date__lte=timezone.now())
        if q:
            qs = qs.filter(Q(title__icontains=q) | Q(text__icontains=q))
        return qs.order_by('-published_date')


class PostDetail(generics.RetrieveAPIView):
    """API: 단일 게시물 상세를 JSON으로 반환합니다."""
    queryset = Post.objects.all()
    serializer_class = PostSerializer


# Create your views here.
def post_list(request):
    posts = Post.objects.filter(published_date__lte=timezone.now()).order_by('published_date')
    return render(request, 'blog/post_list.html', {'posts': posts})


def js_test(request):
    """간단한 JS 테스트용 뷰: blog/js_test.html 템플릿을 렌더합니다."""
    return render(request, 'blog/js_test.html')

def post_detail(request, pk):
    post = get_object_or_404(Post, pk=pk)
    return render(request, 'blog/post_detail.html', {'post': post})

def post_new(request):
    if request.method == "POST":
        form = PostForm(request.POST)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm()
    return render(request, 'blog/post_edit.html', {'form': form})

def post_edit(request, pk):
    post = get_object_or_404(Post, pk=pk)
    if request.method == "POST":
        form = PostForm(request.POST, instance=post)
        if form.is_valid():
            post = form.save(commit=False)
            post.author = request.user
            post.published_date = timezone.now()
            post.save()
            return redirect('post_detail', pk=post.pk)
    else:
        form = PostForm(instance=post)
    return render(request, 'blog/post_edit.html', {'form': form})

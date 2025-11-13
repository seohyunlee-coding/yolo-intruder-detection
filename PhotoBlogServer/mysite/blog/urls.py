from django.urls import path, include
from . import views
from rest_framework import routers

router = routers.DefaultRouter()
router.register('Post', views.blogImage)

app_name = 'blog'

urlpatterns = [
    path('', views.post_list, name='post_list'),
    path('post/<int:pk>/', views.post_detail, name='post_detail'),
    path('post/new/', views.post_new, name='post_new'),
    path('post/<int:pk>/edit/', views.post_edit, name='post_edit'),
    path('js_test/', views.js_test, name='js_test'),
    # API endpoints (readable JSON)
    path('api/posts/', views.PostList.as_view(), name='api_post_list'),
    path('api/posts/search/', views.PostSearch.as_view(), name='api_post_search'),
    path('api/posts/<int:pk>/', views.PostDetail.as_view(), name='api_post_detail'),
    path('api_root/', include(router.urls)),
] 

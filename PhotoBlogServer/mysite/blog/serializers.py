from blog.models import Post
from rest_framework import serializers
from django.contrib.auth.models import User
from django.utils import timezone


class PostSerializer(serializers.HyperlinkedModelSerializer):
	author = serializers.PrimaryKeyRelatedField(queryset=User.objects.all())

	class Meta:
		model = Post
		# include 'id' so clients can discover the PK to use for update/delete
		fields = ('id', 'author', 'title', 'text', 'created_date', 'published_date', 'image')

	def create(self, validated_data):
		"""API로 생성할 때 published_date가 비어있으면 현재 시각으로 설정합니다.
		이렇게 하면 API로 올린 게시물이 메인 리스트의 필터(published_date__lte=now)에서 제외되지 않습니다.
		"""
		if not validated_data.get('published_date'):
			validated_data['published_date'] = timezone.now()
		return super().create(validated_data)
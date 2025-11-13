import os
import cv2
import pathlib
import requests
import logging
from datetime import datetime

_LOG = logging.getLogger(__name__)

class ChangeDetection:
    result_prev = []
    HOST = 'http://127.0.0.1:8000'
    username = 'new'
    password = 'mynewblog'
    token = ''
    title = ''
    text = ''

    def __init__(self, names):
        self.result_prev = [0 for i in range(len(names))]
        # attempt authentication but don't raise to caller
        try:
            # token endpoint expects x-www-form-urlencoded body
            res = requests.post(
                self.HOST + '/api-token-auth/',
                data={
                    'username': self.username,
                    'password': self.password,
                },
                timeout=5,
            )
            res.raise_for_status()
            self.token = res.json().get('token', '')
            if not self.token:
                _LOG.warning('Authentication succeeded but no token received')
            print(f"[ChangeDetection] auth token: {self.token!r}")
        except Exception as e:
            _LOG.warning(f'ChangeDetection auth failed: {e}')
            print(f"[ChangeDetection] auth failed: {e}")
            self.token = ''

    def add(self, names, detected_current, save_dir, image):
        # Identify newly appeared classes (transition 0 -> 1)
        newly_added = []
        for i in range(min(len(self.result_prev), len(detected_current))):
            if self.result_prev[i] == 0 and detected_current[i] == 1:
                newly_added.append(names[i])

        # Update stored detection state
        self.result_prev = detected_current[:]  # 객체 검출 상태 저장

        # If any new object appeared, prepare title/text and send
        if newly_added:
            # Title: comma-joined newly added class names
            self.title = ", ".join(newly_added)

            # Text: include all classes currently detected in this frame
            current_detected = [names[i] for i, v in enumerate(detected_current) if v == 1 and i < len(names)]
            self.text = ", ".join(current_detected)

            self.send(save_dir, image)

    def send(self, save_dir, image):
        now = datetime.now()
        iso_now = now.isoformat()
        today = now
        save_path = pathlib.Path(os.getcwd()) / save_dir / 'detected' / str(today.year) / str(today.month) / str(today.day)
        pathlib.Path(save_path).mkdir(parents=True, exist_ok=True)
        full_path = save_path / '{0}-{1}-{2}-{3}.jpg'.format(today.hour, today.minute, today.second, today.microsecond)
        dst = cv2.resize(image, dsize=(320, 240), interpolation=cv2.INTER_AREA)
        cv2.imwrite(full_path, dst)
        # 인증이 필요한 요청에 아래의 headers를 붙임
        # Use Token auth header (DRF token auth) if token exists
        auth_header = f'Token {self.token}' if self.token else ''
        # Do NOT set Content-Type when sending files; requests will set multipart/form-data automatically
        headers = {}
        if auth_header:
            # use standard header capitalization
            headers['Authorization'] = auth_header

        # Post Create: send form-data (multipart) with author, title, text, image
        # author fixed to '1' per user instruction
        author_value = '1'
        data = {
            'author': author_value,
            'title': self.title,
            'text': self.text,
        }
        print(f"[ChangeDetection] POST form-data fields: {{'author': {author_value!r}, 'title': {self.title!r}}}")
        try:
            with open(full_path, 'rb') as f:
                # include filename and explicit MIME type for the image
                files = {'image': (full_path.name, f, 'image/jpeg')}
                res = requests.post(self.HOST + '/api_root/Post/', data=data, files=files, headers=headers, timeout=10)
                print(f"[ChangeDetection] POST {self.HOST + '/api_root/Post/'} returned {res.status_code}")
                print(f"[ChangeDetection] response body: {res.text}")
                try:
                    res.raise_for_status()
                    _LOG.info(f'Post successful: {res.status_code}')
                except Exception:
                    _LOG.warning(f'Post returned status {res.status_code}: {res.text}')
        except Exception as e:
            _LOG.warning(f'Failed to send detection post: {e}')
            print(f"[ChangeDetection] Failed to send detection post: {e}")

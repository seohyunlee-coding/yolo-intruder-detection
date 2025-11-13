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
    # align credentials with PhotoViewer app
    username = 'admin'
    password = 'myblog1008'
    token = ''
    title = ''
    text = ''

    def get_token(self, force_refresh: bool = False) -> str:
        """Obtain token: prefer environment `BLOG_API_TOKEN`, else request remote token.
        If force_refresh True, ignore env and request anew.
        """
        # env override
        if not force_refresh:
            env = os.environ.get('BLOG_API_TOKEN')
            if env:
                return env

        token_url = 'https://cwijiq.pythonanywhere.com/api-token-auth/'
        # try up to 3 times with increased timeout
        for attempt in range(3):
            try:
                # PhotoViewer sends JSON body
                res = requests.post(
                    token_url,
                    json={'username': self.username, 'password': self.password},
                    timeout=10,
                )
                if res.status_code == 200:
                    body = res.json()
                    tok = body.get('token', '')
                    if tok:
                        return tok
                else:
                    _LOG.warning(f'get_token attempt {attempt+1} returned {res.status_code}: {res.text}')
            except Exception as e:
                _LOG.warning(f'get_token attempt {attempt+1} error: {e}')
        return ''

    def __init__(self, names):
        self.result_prev = [0 for i in range(len(names))]
        # get token (env preferred, else remote)
        try:
            self.token = self.get_token()
            if not self.token:
                _LOG.warning('No token obtained in constructor')
            print(f"[ChangeDetection] auth token: {self.token!r}")
        except Exception as e:
            _LOG.warning(f'ChangeDetection auth init failed: {e}')
            print(f"[ChangeDetection] auth init failed: {e}")
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
                post_url = 'https://cwijiq.pythonanywhere.com/api_root/Post/'
                res = requests.post(post_url, data=data, files=files, headers=headers, timeout=10)
                print(f"[ChangeDetection] POST {post_url} returned {res.status_code}")
                print(f"[ChangeDetection] response body: {res.text}")
                # If unauthorized, try refreshing token once and retry
                if res.status_code == 401:
                    _LOG.info('POST returned 401 - attempting token refresh and retry')
                    # refresh token (ignore env)
                    new_token = self.get_token(force_refresh=True)
                    if new_token:
                        self.token = new_token
                        headers['Authorization'] = f'Token {self.token}'
                        try:
                            res.close()
                        except Exception:
                            pass
                        res = requests.post(post_url, data=data, files=files, headers=headers, timeout=10)
                        print(f"[ChangeDetection] POST retry {post_url} returned {res.status_code}")
                        print(f"[ChangeDetection] retry response body: {res.text}")
                try:
                    res.raise_for_status()
                    _LOG.info(f'Post successful: {res.status_code}')
                except Exception:
                    _LOG.warning(f'Post returned status {res.status_code}: {res.text}')
        except Exception as e:
            _LOG.warning(f'Failed to send detection post: {e}')
            print(f"[ChangeDetection] Failed to send detection post: {e}")

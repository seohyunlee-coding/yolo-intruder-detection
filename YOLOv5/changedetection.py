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

        # track last post id and persistent list of uploaded posts
        self.last_post_id = None
        self._posted_store = pathlib.Path(os.getcwd()) / 'YOLOv5_uploaded_posts.json'
        # ensure file exists
        try:
            if not self._posted_store.exists():
                self._posted_store.write_text('[]', encoding='utf-8')
        except Exception:
            # ignore store creation errors
            pass

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

            # save a local copy of the detected frame for offline review
            try:
                # save to user's Desktop to make files easy to find
                local_dir = pathlib.Path.home() / 'Desktop' / 'YOLOv5_local_saved'
                local_dir.mkdir(parents=True, exist_ok=True)
                now = datetime.now()
                fname = f"{now.strftime('%Y%m%d_%H%M%S_%f')}_{self.title.replace(' ', '_')}.jpg"
                local_path = local_dir / fname
                # image is expected to be BGR (cv2) image
                try:
                    cv2.imwrite(str(local_path), image)
                except Exception:
                    # fallback: if image is numpy array in another shape, try to convert
                    try:
                        i = image.copy()
                        cv2.imwrite(str(local_path), i)
                    except Exception:
                        _LOG.debug('failed to save local detection image')
            except Exception:
                _LOG.debug('local save failed')

            # send to remote server (if configured)
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
                    # try to parse returned JSON and save post id
                    try:
                        body = res.json()
                        post_id = body.get('id') or body.get('pk')
                        if post_id:
                            self.last_post_id = int(post_id)
                            # append to persistent store
                            try:
                                import json
                                arr = json.loads(self._posted_store.read_text(encoding='utf-8'))
                                arr.append({'id': self.last_post_id, 'title': self.title, 'ts': iso_now})
                                self._posted_store.write_text(json.dumps(arr, ensure_ascii=False), encoding='utf-8')
                            except Exception:
                                pass
                            # enforce server limit after successful post (default 20)
                            try:
                                self.enforce_server_limit(10)
                            except Exception:
                                _LOG.debug('enforce_server_limit failed')
                    except Exception:
                        _LOG.debug('Could not parse post response JSON')
                except Exception:
                    _LOG.warning(f'Post returned status {res.status_code}: {res.text}')
        except Exception as e:
            _LOG.warning(f'Failed to send detection post: {e}')
            print(f"[ChangeDetection] Failed to send detection post: {e}")

    def delete_post(self, post_id: int = None) -> bool:
        """Delete a Post by id using the REST API. If post_id is None, use last stored id or the last entry from the persistent store.

        Returns True if deleted (HTTP 204 or 200), False otherwise.
        """
        # resolve id
        pid = None
        if post_id:
            pid = int(post_id)
        elif self.last_post_id:
            pid = int(self.last_post_id)
        else:
            # try persistent store
            try:
                import json
                arr = json.loads(self._posted_store.read_text(encoding='utf-8'))
                if arr:
                    pid = int(arr[-1].get('id'))
            except Exception:
                pid = None

        if not pid:
            _LOG.warning('delete_post: no post id available to delete')
            return False

        post_detail_url = f'https://cwijiq.pythonanywhere.com/api_root/Post/{pid}/'
        headers = {}
        if self.token:
            headers['Authorization'] = f'Token {self.token}'

        try:
            res = requests.delete(post_detail_url, headers=headers, timeout=10)
            print(f"[ChangeDetection] DELETE {post_detail_url} returned {res.status_code}")
            if res.status_code in (200, 204):
                # remove from persistent store if present
                try:
                    import json
                    arr = json.loads(self._posted_store.read_text(encoding='utf-8'))
                    arr = [e for e in arr if int(e.get('id')) != pid]
                    self._posted_store.write_text(json.dumps(arr, ensure_ascii=False), encoding='utf-8')
                except Exception:
                    pass
                # clear last_post_id if it matched
                if self.last_post_id == pid:
                    self.last_post_id = None
                return True
            elif res.status_code == 401:
                # try refresh token and retry once
                _LOG.info('DELETE returned 401 - attempting token refresh and retry')
                new_token = self.get_token(force_refresh=True)
                if new_token:
                    self.token = new_token
                    headers['Authorization'] = f'Token {self.token}'
                    res = requests.delete(post_detail_url, headers=headers, timeout=10)
                    print(f"[ChangeDetection] DELETE retry {post_detail_url} returned {res.status_code}")
                    if res.status_code in (200, 204):
                        try:
                            import json
                            arr = json.loads(self._posted_store.read_text(encoding='utf-8'))
                            arr = [e for e in arr if int(e.get('id')) != pid]
                            self._posted_store.write_text(json.dumps(arr, ensure_ascii=False), encoding='utf-8')
                        except Exception:
                            pass
                        if self.last_post_id == pid:
                            self.last_post_id = None
                        return True
            _LOG.warning(f'DELETE returned status {res.status_code}: {res.text}')
            return False
        except Exception as e:
            _LOG.warning(f'DELETE request failed: {e}')
            print(f"[ChangeDetection] DELETE request failed: {e}")
            return False

    def enforce_server_limit(self, max_posts: int = 10) -> int:
        """Ensure the server has at most `max_posts` posts by deleting oldest posts.

        Returns the number of deleted posts.
        """
        post_list_url = 'https://cwijiq.pythonanywhere.com/api_root/Post/'
        headers = {}
        if self.token:
            headers['Authorization'] = f'Token {self.token}'

        try:
            res = requests.get(post_list_url, headers=headers, timeout=10)
            if res.status_code == 401:
                # token may be invalid: refresh token and retry once (mimic PhotoViewer behavior)
                _LOG.info('enforce_server_limit GET returned 401 - refreshing token and retrying')
                new_token = self.get_token(force_refresh=True)
                if new_token:
                    headers['Authorization'] = f'Token {new_token}'
                    res = requests.get(post_list_url, headers=headers, timeout=10)
        except Exception as e:
            _LOG.warning(f'enforce_server_limit GET failed: {e}')
            return 0

        if not res.ok:
            _LOG.warning(f'enforce_server_limit GET returned {res.status_code}: {res.text}')
            return 0

        try:
            body = res.json()
        except Exception:
            _LOG.warning('enforce_server_limit: could not parse JSON from GET')
            return 0

        # handle DRF pagination: {'count':.., 'results':[...]} or direct list
        if isinstance(body, dict) and 'results' in body:
            posts = body['results']
        elif isinstance(body, list):
            posts = body
        else:
            _LOG.warning('enforce_server_limit: unexpected GET format')
            return 0

        total = len(posts)
        if total <= max_posts:
            return 0

        # sort by created_date if available, else keep order
        def key_created(p):
            return p.get('created_date') or p.get('published_date') or ''

        posts_sorted = sorted(posts, key=key_created)
        to_delete = posts_sorted[0: total - max_posts]
        deleted = 0
        for p in to_delete:
            pid = p.get('id') or p.get('pk')
            if not pid:
                continue
            try:
                ok = self.delete_post(int(pid))
                if ok:
                    deleted += 1
            except Exception:
                _LOG.warning(f'enforce_server_limit: failed to delete {pid}')
        _LOG.info(f'enforce_server_limit removed {deleted} posts (total was {total})')
        return deleted

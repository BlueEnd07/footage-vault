"""Resumable-upload probing without touching YouTube."""
from vault import youtube


class Resp(dict):
    def __init__(self, status, **headers):
        super().__init__(headers)
        self.status = status


class Http:
    def __init__(self, resp):
        self.resp = resp

    def request(self, uri, method=None, headers=None):
        return self.resp, b'{"id": "abc"}'


class Request:
    def __init__(self, resp):
        self.http = Http(resp)

    def postproc(self, resp, content):
        return {"id": "abc"}


def probe(resp):
    return youtube._probe_session(Request(resp), "https://upload.example/session", 20_000_000)


def test_complete_returns_the_video_id():
    assert probe(Resp(200)) == ("complete", None, {"id": "abc"})


def test_range_means_resume_from_the_next_byte():
    assert probe(Resp(308, range="bytes=0-8388607")) == ("incomplete", 8_388_608, None)


def test_a_308_without_range_is_not_resumed_from_zero():
    assert probe(Resp(308)) == ("expired", None, None)


def test_a_range_covering_the_whole_file_starts_a_new_session():
    assert probe(Resp(308, range="bytes=0-19999999")) == ("expired", None, None)


def test_gone_and_missing_sessions_expire():
    assert probe(Resp(404))[0] == "expired"
    assert probe(Resp(410))[0] == "expired"

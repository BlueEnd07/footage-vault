from datetime import datetime, timedelta

from vault import auth


class FakeRequest:
    def __init__(self, method="POST", headers=None):
        self.method = method
        self.headers = headers or {}


def test_host_name_strips_port_and_brackets():
    assert auth.host_name("localhost:8765") == "localhost"
    assert auth.host_name("[::1]:8765") == "::1"
    assert auth.host_name("[::1]") == "::1"
    assert auth.host_name("") is None


def test_only_real_loopback_names_are_local():
    assert auth.is_local_host("localhost:8765")
    assert auth.is_local_host("127.0.0.1")
    assert auth.is_local_host("[::1]:8765")
    assert auth.is_local_host("127.1.2.3")  # any 127/8 address is loopback
    assert not auth.is_local_host("localhost.evil.example")
    assert not auth.is_local_host("127.0.0.1.evil.example")
    assert not auth.is_local_host("192.168.1.10:8765")
    assert not auth.is_local_host(None)


def test_same_origin():
    assert auth.same_origin("http://localhost:8765", "localhost:8765")
    assert auth.same_origin("https://pc.tailnet.ts.net", "pc.tailnet.ts.net")
    assert auth.same_origin("http://localhost:8765", "localhost:8765")
    assert not auth.same_origin("http://localhost", "localhost:8765")  # a different port
    assert not auth.same_origin("http://localhost:9999", "localhost:8765")
    assert not auth.same_origin("https://evil.example", "localhost:8765")
    assert not auth.same_origin("null", "localhost:8765")
    assert not auth.same_origin(None, "localhost:8765")


def test_cross_site_uses_fetch_metadata_and_origin():
    assert auth.cross_site(FakeRequest("GET", {"origin": "https://evil.example"})) is False
    assert auth.cross_site(FakeRequest(headers={"sec-fetch-site": "cross-site"})) is True
    assert auth.cross_site(FakeRequest(headers={"sec-fetch-site": "same-origin"})) is False
    assert auth.cross_site(FakeRequest(headers={"origin": "https://evil.example"})) is True
    assert auth.cross_site(FakeRequest(headers={"origin": "http://localhost:8765",
                                                "host": "localhost:8765"})) is False


def test_session_round_trip_and_expiry():
    token, digest = auth.new_session()
    sessions = {digest: auth.session_expiry()}
    assert auth.valid_session(token, sessions)
    assert not auth.valid_session(token + "x", sessions)
    assert not auth.valid_session(token, None)
    past = (datetime.now() - timedelta(seconds=1)).strftime("%Y-%m-%d %H:%M:%S")
    assert not auth.valid_session(token, {digest: past})


def test_prune_sessions_drops_expired_and_caps_count():
    now = datetime(2026, 1, 1, 12, 0, 0)
    sessions = {
        "old": (now - timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S"),
        "new": (now + timedelta(days=1)).strftime("%Y-%m-%d %H:%M:%S"),
        "later": (now + timedelta(days=2)).strftime("%Y-%m-%d %H:%M:%S"),
        "bad": "not a date",
    }
    pruned = auth.prune_sessions(sessions, now=now, limit=1)
    assert pruned == {"later": sessions["later"]}


def test_login_limiter_delays_after_repeated_failures():
    lim = auth.LoginLimiter(limit=2, base_delay=10)
    assert lim.retry_after("a") == 0
    lim.fail("a")
    lim.fail("a")
    assert lim.retry_after("a") > 0
    assert lim.retry_after("b") == 0
    lim.reset("a")
    assert lim.retry_after("a") == 0

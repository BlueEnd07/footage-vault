"""Automatic events: clips recorded close together in time (and place) belong together."""
from collections import Counter
from datetime import datetime, timedelta
from math import asin, cos, radians, sin, sqrt

SPLIT_KM = 50  # two clips this far apart are different events even if close in time


def _km(a: tuple[float, float], b: tuple[float, float]) -> float:
    (la1, lo1), (la2, lo2) = map(lambda p: (radians(p[0]), radians(p[1])), (a, b))
    h = sin((la2 - la1) / 2) ** 2 + cos(la1) * cos(la2) * sin((lo2 - lo1) / 2) ** 2
    return 2 * 6371 * asin(sqrt(h))


def _part_of_day(t: datetime) -> str:
    h = t.hour
    return "morning" if 5 <= h < 12 else "afternoon" if h < 17 else "evening" if h < 21 else "night"


def _label(start: datetime, end: datetime) -> str:
    year = "" if start.year == datetime.now().year else f" {start.year}"
    # A night out that runs past midnight still counts as the evening it started.
    if end - start < timedelta(hours=18):
        return f"{start:%a} {start.day} {start:%b}{year}, {_part_of_day(start)}"
    if start.month == end.month:
        return f"{start.day}–{end.day} {start:%b}{year}"
    return f"{start.day} {start:%b} – {end.day} {end:%b}{year}"


def build(videos: list[dict], gap_hours: float = 3) -> list[dict]:
    """videos: dicts with id, recorded_at, lat, lon, place. Returns events, newest first."""
    vs = sorted(videos, key=lambda v: v["recorded_at"])
    groups, cur, prev_t, last_loc = [], [], None, None
    for v in vs:
        t = datetime.strptime(v["recorded_at"], "%Y-%m-%d %H:%M:%S")
        loc = (v["lat"], v["lon"]) if v.get("lat") is not None else None
        if cur:
            too_late = t - prev_t > timedelta(hours=gap_hours)
            too_far = loc and last_loc and _km(loc, last_loc) > SPLIT_KM
            if too_late or too_far:
                groups.append(cur)
                cur, last_loc = [], None
        cur.append(v)
        prev_t = t
        last_loc = loc or last_loc
    if cur:
        groups.append(cur)

    events = []
    for g in groups:
        if len(g) < 2:
            continue
        start = datetime.strptime(g[0]["recorded_at"], "%Y-%m-%d %H:%M:%S")
        end = datetime.strptime(g[-1]["recorded_at"], "%Y-%m-%d %H:%M:%S")
        places = Counter(v["place"] for v in g if v.get("place"))
        place = places.most_common(1)[0][0] if places else None
        when = _label(start, end)
        events.append({
            "id": f"e{g[0]['id']}_{len(g)}",
            "name": f"{place} · {when}" if place else when,
            "place": place,
            "start": g[0]["recorded_at"],
            "end": g[-1]["recorded_at"],
            "count": len(g),
            "video_ids": [v["id"] for v in g],
            "cover": g[len(g) // 2]["id"],
        })
    events.reverse()
    return events

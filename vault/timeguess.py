"""When was a video really recorded? From its file name and YouTube details (same rules as the Android app).

Clips shot on the same day should sit together even when one copy lost its recording time
(uploaded straight to YouTube, copied, downloaded, edited).
"""
import json
import re
from datetime import date, datetime, timedelta

_COMPACT = re.compile(r"(?<!\d)(20\d{2})(\d{2})(\d{2})[_\-T ]?(\d{2})(\d{2})(\d{2})(\d{0,3})(?!\d{4})")
_DASHED = re.compile(r"(20\d{2})-(\d{2})-(\d{2})(?:[ _T]|\s+at\s+)(\d{2})[.:\-](\d{2})(?:[.:\-](\d{2}))?")
_COMPACT_DATE = re.compile(r"(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)")
_DASHED_DATE = re.compile(r"(?<!\d)(20\d{2})-(\d{2})-(\d{2})(?!\d)")
_FV = re.compile(r"fv:(\{.*\})")
_ORIGINAL = re.compile(r"Original file:\s*(\S.*?)\s*$", re.MULTILINE)


def _at(y, mo, d, h=None, mi=None, s=None, frac=None):
    try:
        day = date(int(y), int(mo), int(d))
        if day > date.today() + timedelta(days=2):
            return None
        if h is None:
            return datetime.combine(day, datetime.min.time()).replace(hour=12)
        ms = int((frac or "0").ljust(3, "0")[:3])
        return datetime(day.year, day.month, day.day, int(h), int(mi), int(s or 0), ms * 1000)
    except ValueError:
        return None


def from_name(name: str):
    """(local datetime, has_time) from a file name or title, or None."""
    if m := _COMPACT.search(name):
        if t := _at(*m.groups()):
            return t, True
    if m := _DASHED.search(name):
        if t := _at(*m.groups()):
            return t, True
    for rx in (_COMPACT_DATE, _DASHED_DATE):
        if m := rx.search(name):
            if t := _at(*m.groups()):
                return t, False
    return None


def _parse_iso(value):
    try:
        return datetime.fromisoformat((value or "").replace("Z", "+00:00"))
    except ValueError:
        return None


def for_youtube(title: str, description: str, recording_date: str | None, published: str | None) -> datetime | None:
    """Footage Vault's own record > time in name/title > YouTube recording time > date-only (midday) > upload date.
    Returns local time without tzinfo."""
    if m := _FV.search(description or ""):
        try:
            r = json.loads(m.group(1)).get("r")
            if r:
                return datetime.fromtimestamp(r / 1000)
        except (ValueError, TypeError):
            pass
    names = []
    if m := _ORIGINAL.search(description or ""):
        names.append(m.group(1))
    names.append(title or "")
    guesses = [g for g in (from_name(n) for n in names) if g]
    for t, has_time in guesses:
        if has_time:
            return t
    rec = _parse_iso(recording_date)
    # YouTube keeps only the date for most uploads (00:00:00 UTC): a date, not a time.
    if rec and (rec.hour, rec.minute, rec.second) != (0, 0, 0):
        return rec.astimezone().replace(tzinfo=None)
    if guesses:
        return guesses[0][0]
    if rec:
        return datetime(rec.year, rec.month, rec.day, 12)
    pub = _parse_iso(published)
    return pub.astimezone().replace(tzinfo=None) if pub else None

import json

from vault import config


def test_first_run_creates_eight_digit_pin_and_saves(tmp_path):
    s = config.load_settings()
    assert len(s["pin"]) == 8 and s["pin"].isdigit()
    assert config.SETTINGS_PATH.exists()
    on_disk = json.loads(config.SETTINGS_PATH.read_text("utf-8"))
    assert on_disk["pin"] == s["pin"]


def test_saved_values_are_read_back(tmp_path):
    s = config.load_settings()
    s["delete_after_days"] = 9
    s["watch_folders"] = [{"path": str(tmp_path), "source": "pc"}]
    config.save_settings(s)
    again = config.load_settings()
    assert again["delete_after_days"] == 9
    assert again["watch_folders"] == [{"path": str(tmp_path), "source": "pc"}]
    assert again["pin"] == s["pin"]


def test_corrupt_settings_are_kept_and_replaced_by_defaults():
    config.SETTINGS_PATH.write_text("{not json", "utf-8")
    s = config.load_settings()
    assert len(s["pin"]) == 8
    backup = config.SETTINGS_PATH.with_suffix(".json.bad")
    assert backup.read_text("utf-8") == "{not json"
    assert config.SETTINGS_PATH.exists()


def test_a_windows_editor_bom_is_accepted():
    body = json.dumps({**config.DEFAULTS, "pin": "12345678", "delete_after_days": 7}, indent=2)
    config.SETTINGS_PATH.write_text(body, "utf-8-sig")
    s = config.load_settings()
    assert s["pin"] == "12345678"
    assert s["delete_after_days"] == 7
    assert not config.SETTINGS_PATH.with_suffix(".json.bad").exists()


def test_bad_session_value_falls_back_to_empty_dict():
    config.save_settings({**config.DEFAULTS, "pin": "12345678", "sessions": ["nope"]})
    assert config.load_settings()["sessions"] == {}


def test_saving_leaves_no_temporary_files(tmp_path):
    config.save_settings({**config.DEFAULTS, "pin": "12345678"})
    leftovers = list(tmp_path.glob(".settings-*.json"))
    assert leftovers == []

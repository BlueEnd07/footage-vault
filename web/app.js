const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const api = async (url, body) => {
  const r = await fetch(url, body === undefined ? undefined
    : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.detail || r.statusText);
  return data;
};
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

let videos = [];
let albums = [];
let events = [];
let people = [];
let peopleInfo = { others: 0, scan: {} };
let status = {};
let view = "photos";          // photos | albums | map
let albumId = null;           // album / event / person shown in the photos view
let eventId = null;
let personId = null;
let filter = new URLSearchParams(location.search).get("filter") || "all";
let selecting = false;
const selected = new Set();
let lastRendered = "";
let openId = null;

const IN_PROGRESS = new Set(["new", "queued", "uploading", "uploaded"]);
const ON_YT = new Set(["on_youtube", "done", "youtube"]);
const PROBLEM = new Set(["failed", "hold"]);
const onPhoneSafe = (v) => v.source === "phone" && ON_YT.has(v.status) && !v.phone_cleared_at;
const FILTERS = {
  fav: { title: "Favourites", test: (v) => v.favorite, empty: "Tap ♡ on a video to add it here." },
  progress: { title: "Uploading", test: (v) => IN_PROGRESS.has(v.status), empty: "Everything is backed up." },
  phone: { title: "Safe to delete from phone", test: onPhoneSafe, empty: "Nothing to delete right now." },
  problems: { title: "Problems", test: (v) => PROBLEM.has(v.status), empty: "No problems. Nice." },
};

// ---------- formatting ----------
const fmtDur = (s) => {
  s = Math.round(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(sec).padStart(2, "0");
};
const fmtSize = (b) => b > 1e9 ? (b / 1e9).toFixed(2) + " GB" : (b / 1e6).toFixed(b < 1e7 ? 1 : 0) + " MB";
const parseDate = (s) => new Date(s.replace(" ", "T"));
const dayKey = (d) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
function dayLabel(d) {
  const today = new Date(), y = new Date(); y.setDate(today.getDate() - 1);
  if (dayKey(d) === dayKey(today)) return "Today";
  if (dayKey(d) === dayKey(y)) return "Yesterday";
  const opts = { weekday: "short", day: "numeric", month: "short" };
  if (d.getFullYear() !== today.getFullYear()) opts.year = "numeric";
  return d.toLocaleDateString(undefined, opts);
}
const albumIds = (v) => (v.albums ? String(v.albums).split(",").map(Number) : []);
const videoFaces = (v) => (v.faces ? String(v.faces).split(",").map((s) => s.split(":").map(Number)) : []);
const personName = (pid) => people.find((p) => p.id === pid)?.name;
const ring = (p) => `<svg class="ring" viewBox="0 0 20 20"><circle class="bg" cx="10" cy="10" r="8"/>
  <circle class="fg" cx="10" cy="10" r="8" stroke-dasharray="50.27" stroke-dashoffset="${50.27 * (1 - Math.max(.04, p))}"/></svg>`;

function statusText(v) {
  switch (v.status) {
    case "new": return "Preparing preview";
    case "queued": return status.connected ? (status.user_paused ? "Waiting · uploads paused" : "Waiting to upload") : "Waiting · YouTube not connected";
    case "uploading": return `Uploading to YouTube · ${Math.round(v.progress * 100)}%`;
    case "uploaded": return "Uploaded · YouTube is processing";
    case "on_youtube": {
      const when = v.delete_after ? parseDate(v.delete_after).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
      if (v.source === "youtube") return `Backed up · Takeout file goes to Recycle Bin ${when}`;
      return v.source === "pc" ? `Backed up · original goes to Recycle Bin ${when}` : `Backed up · PC copy cleared ${when}`;
    }
    case "done": return "Backed up · original deleted";
    case "hold": return "Backed up · original kept (a check failed)";
    case "failed": return "Upload failed";
    case "youtube": return `On YouTube (${v.privacy || "uploaded directly"})`;
  }
  return v.status;
}

// ---------- dialogs + snackbar (instead of prompt / confirm / alert) ----------
let dialogCancel = null;
function dialog({ title, text = "", input = null, ok = "OK", cancel = "Cancel", danger = false }) {
  return new Promise((resolve) => {
    $("#dlgTitle").textContent = title;
    $("#dlgText").innerHTML = text;
    $("#dlgText").hidden = !text;
    const inp = $("#dlgInput");
    inp.hidden = input === null;
    if (input !== null) { inp.value = input.value || ""; inp.placeholder = input.placeholder || ""; }
    $("#dlgOk").textContent = ok;
    $("#dlgOk").className = "btn " + (danger ? "danger" : "filled");
    $("#dlgCancel").hidden = cancel === null;
    $("#dlgCancel").textContent = cancel || "";
    const done = (val) => { $("#dialog").hidden = true; dialogCancel = null; resolve(val); };
    $("#dlgForm").onsubmit = (e) => { e.preventDefault(); done(input !== null ? inp.value.trim() : true); };
    $("#dlgCancel").onclick = () => done(null);
    dialogCancel = () => done(null);
    $("#dialog").hidden = false;
    setTimeout(() => (input !== null ? inp : $("#dlgOk")).focus(), 30);
  });
}
const ask = (title, value = "", placeholder = "", ok = "Save", text = "") => dialog({ title, text, input: { value, placeholder }, ok });
const confirmBox = (title, text, ok = "OK", danger = false) => dialog({ title, text, ok, danger });
const tell = (title, text = "") => dialog({ title, text, cancel: null });
let toastTimer = null;
function toast(msg, action) {
  const t = $("#toast");
  t.innerHTML = `<span>${esc(msg)}</span>` + (action ? `<button class="btn text" id="toastAct">${esc(action.label)}</button>` : "");
  t.hidden = false;
  if (action) $("#toastAct").onclick = () => { t.hidden = true; action.run(); };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 4500);
}

// ---------- navigation ----------
function setView(v, aid = null, eid = null, pid = null) {
  view = v;
  albumId = aid; eventId = eid; personId = pid;
  if (v !== "photos" || aid || eid || pid) filter = "all";
  exitSelect();
  history.replaceState(null, "", filter !== "all" && v === "photos" && !(aid || eid || pid) ? `/?filter=${filter}` : "/");
  $("#photosView").hidden = v !== "photos";
  $("#albumsView").hidden = v !== "albums";
  $("#mapView").hidden = v !== "map";
  renderNav();
  renderPersonFaces();
  window.scrollTo(0, 0);
  lastRendered = "";
  render();
}
function setFilter(f) {
  filter = f;
  setView("photos");
}
function renderNav() {
  const sub = albumId || eventId || personId;
  const active = view === "photos" && sub ? "albums" : view;
  $$("[data-view]").forEach((b) => b.classList.toggle("active", b.dataset.view === active && (view !== "photos" || filter === "all" || sub)));
  $$(".nav-item[data-filter]").forEach((b) => b.classList.toggle("active", view === "photos" && !sub && b.dataset.filter === filter));
}
$$("[data-view]").forEach((b) => b.addEventListener("click", () => { filter = "all"; setView(b.dataset.view); }));
$$("[data-filter]").forEach((b) => b.addEventListener("click", () => setFilter(b.dataset.filter)));
$("#backToAlbums").onclick = () => {
  if (filter !== "all" && !(albumId || eventId || personId)) { filter = "all"; setView("photos"); }
  else setView("albums");
};
window.addEventListener("scroll", () => document.body.classList.toggle("scrolled", window.scrollY > 4), { passive: true });

// ---------- gallery ----------
const currentEvent = () => events.find((e) => e.id === eventId);
const currentPerson = () => people.find((p) => p.id === personId);
function visible() {
  if (albumId) return videos.filter((v) => albumIds(v).includes(albumId));
  if (eventId) { const ids = new Set(currentEvent()?.video_ids || []); return videos.filter((v) => ids.has(v.id)); }
  if (personId) return videos.filter((v) => videoFaces(v).some(([, pid]) => pid === personId));
  if (FILTERS[filter]) return videos.filter(FILTERS[filter].test);
  return videos;
}

function tile(v) {
  const hasThumb = v.status !== "new" || v.youtube_id;
  const ar = v.width && v.height ? Math.min(2.4, Math.max(0.5, v.width / v.height)) : 16 / 9;
  let badge = "";
  if (v.status === "uploading") badge = `<span class="t-badge" title="Uploading">${ring(v.progress)}</span>`;
  else if (IN_PROGRESS.has(v.status)) badge = `<span class="t-badge" title="${esc(statusText(v))}">${icon("cloud_upload")}</span>`;
  else if (PROBLEM.has(v.status)) badge = `<span class="t-badge bad" title="${esc(statusText(v))}">${icon("error")}</span>`;
  const sel = selected.has(v.id);
  return `<button class="tile${sel ? " selected" : ""}" data-id="${v.id}" style="flex:${ar.toFixed(3)} 1 ${Math.round(200 * ar)}px"
      aria-label="${esc(v.name)}" title="${esc(v.name)}">
    ${hasThumb ? `<img loading="lazy" src="/media/thumbs/${v.id}.jpg" alt="">` : `<div class="ph">Preparing…</div>`}
    <span class="t-sel">${icon(sel ? "check_circle" : "check_circle_outline")}</span>
    <span class="t-dur">${fmtDur(v.duration)}${icon("play_circle")}</span>
    ${v.favorite ? `<span class="t-fav">${icon("favorite")}</span>` : ""}${badge}</button>`;
}

function renderGallery() {
  const list = visible();
  let html = "", day = "", open = false;
  for (const v of list) {
    const d = parseDate(v.recorded_at), k = dayKey(d);
    if (k !== day) {
      if (open) html += "</div>";
      const ids = list.filter((x) => dayKey(parseDate(x.recorded_at)) === k).map((x) => x.id);
      const all = ids.every((id) => selected.has(id));
      html += `<div class="day"><button class="day-sel${all && selecting ? " on" : ""}" data-day="${ids.join(",")}" aria-label="Select this day">
        ${icon(all && selecting ? "check_circle" : "check_circle_outline")}</button><h3>${dayLabel(d)}</h3>${v.place ? `<span class="place">${esc(v.place)}</span>` : ""}</div><div class="grid">`;
      open = true; day = k;
    }
    html += tile(v);
  }
  if (open) html += "</div>";
  $("#gallery").innerHTML = html;

  const empty = $("#empty");
  empty.hidden = list.length > 0;
  const f = FILTERS[filter];
  empty.innerHTML = albumId ? `${icon("collections", "big")}<h2>This album is empty</h2><p>Select videos and choose <b>Add to album</b>.</p>`
    : (eventId || personId) ? `<p>Nothing here.</p>`
    : f ? `${icon("check_circle_outline", "big")}<h2>${f.title}</h2><p>${f.empty}</p>`
    : `${icon("photo_library", "big")}<h2>No videos yet</h2><p>Tap <b>Upload</b> to add videos from this device${status.local ? ", or import a folder on this PC" : ""}.</p>`;

  renderCounts();
  renderFreeBar();
  renderSubHeader();
}

function renderCounts() {
  const n = (fn) => videos.filter(fn).length || "";
  for (const [k, f] of Object.entries(FILTERS)) {
    const id = { fav: "Fav", progress: "Progress", phone: "Phone", problems: "Problems" }[k];
    $("#n" + id).textContent = n(f.test);
    $("#u" + id).textContent = n(f.test);
  }
}

function renderSubHeader() {
  const h = $("#albumHeader");
  const f = FILTERS[filter];
  const sub = albumId || eventId || personId;
  h.hidden = !sub && !f;
  if (h.hidden) return;
  const list = visible();
  let title = "", subtitle = plural(list.length, "video"), actions = "";
  if (albumId) {
    const a = albums.find((x) => x.id === albumId);
    if (!a) return setView("albums");
    title = a.name;
    subtitle += " · private YouTube playlist";
    actions = `<button class="btn filled" id="shareAlbum">${icon("share")}Share</button>
               <button class="btn outline" id="renameAlbum">${icon("edit")}Rename</button>
               <button class="btn text" id="deleteAlbum">Delete album</button>`;
  } else if (eventId) {
    const e = currentEvent();
    if (!e) return setView("albums");  // event changed shape (new clips arrived)
    title = e.name;
    actions = `<button class="btn filled" id="saveEvent">${icon("library_add")}Save as album</button>`;
  } else if (personId) {
    const p = currentPerson();
    if (!p) return setView("albums");  // merged or hidden
    title = p.name || "Who is this?";
    actions = `<button class="btn filled" id="namePerson">${icon("edit")}${p.name ? "Rename" : "Add name"}</button>
               <button class="btn outline" id="mergePerson">${icon("call_merge")}Same person as…</button>
               <button class="btn text" id="hidePerson">Hide</button>`;
  } else {
    title = f.title;
    if (filter === "phone" && list.length)
      actions = `<button class="btn filled" id="freeBtn2">${icon("delete_sweep")}Free up space</button>`;
  }
  $("#albumTitle").textContent = title;
  $("#albumSub").textContent = subtitle;
  $("#subActions").innerHTML = actions;
  const on = (id, fn) => { const b = document.getElementById(id); if (b) b.onclick = fn; };
  on("renameAlbum", renameAlbum); on("deleteAlbum", deleteAlbum); on("saveEvent", saveEvent); on("shareAlbum", shareAlbum);
  on("namePerson", namePerson); on("mergePerson", mergePerson); on("hidePerson", hidePerson);
  on("freeBtn2", openFreeSheet);
}

// ---------- library ----------
const card = (attr, cover, name, sub, cls = "") => `
  <button class="album-card ${cls}" ${attr}>
    <div class="cover">${cover ? `<img loading="lazy" src="/media/thumbs/${cover}.jpg" alt="">` : icon("collections")}</div>
    <div class="name">${esc(name)}</div><div class="count">${sub}</div>
  </button>`;
function renderAlbums() {
  renderPeople();
  renderCounts();
  $("#eventsBlock").hidden = !events.length;
  $("#eventGrid").innerHTML = events.map((e) => card(`data-event="${e.id}"`, e.cover, e.name, plural(e.count, "clip"))).join("");
  $("#albumGrid").innerHTML = `<button class="album-card new" id="newAlbumBtn"><div class="cover">${icon("add")}</div>
      <div class="name">New album</div><div class="count">&nbsp;</div></button>` +
    albums.map((a) => card(`data-album="${a.id}"`, a.cover, a.name, plural(a.count, "video"))).join("");
  $("#newAlbumBtn").onclick = newAlbum;
}
$("#albumsView").addEventListener("click", (e) => {
  const c = e.target.closest(".album-card");
  if (!c || c.id === "newAlbumBtn") return;
  if (c.dataset.album) setView("photos", +c.dataset.album);
  else if (c.dataset.event) setView("photos", null, c.dataset.event);
});

function render() {
  const key = [view, albumId, eventId, personId, filter, selecting, [...selected].join(), status.connected, status.user_paused,
    JSON.stringify(videos), JSON.stringify(albums), JSON.stringify(events), JSON.stringify(people)].join("|");
  if (key === lastRendered) return;
  lastRendered = key;
  if (view === "photos") renderGallery();
  else if (view === "albums") renderAlbums();
  else if (view === "map") showMap();
  if (openId) renderDetails();
}

// ---------- albums ----------
async function newAlbum() {
  const name = await ask("New album", "", "Album name", "Create", "It's also created as a private playlist on your YouTube channel.");
  if (!name) return;
  const { id } = await api("/api/albums", { name });
  await refresh();
  setView("photos", id);
  toast("Album created. Select videos and tap Add to album.");
}
async function shareAlbum() {
  let r;
  try { r = await api(`/api/albums/${albumId}/share`, {}); } catch (e) { return tell("Can't share yet", esc(e.message)); }
  const warnVideos = r.private_videos ? `<br><br>${plural(r.private_videos, "video")} in it ${r.private_videos === 1 ? "is" : "are"} private and won't play for others. Make them unlisted in YouTube Studio if you want them seen.` : "";
  if (r.privacy === "private") {
    if (!await confirmBox(`Share “${esc(r.title)}”?`, "This album is a private playlist. To share it, it becomes <b>unlisted</b>: anyone with the link can see it, but it isn't searchable on YouTube." + warnVideos, "Make unlisted & share")) return;
    try { r = await api(`/api/albums/${albumId}/share`, { make_unlisted: true }); } catch (e) { return tell("Couldn't share", esc(e.message)); }
  } else if (warnVideos && !await confirmBox("Share album", warnVideos.slice(8), "Share")) return;
  if (navigator.share) { try { await navigator.share({ title: r.title, url: r.url }); return; } catch { /* cancelled or unsupported: copy instead */ } }
  try { await navigator.clipboard.writeText(r.url); toast("Playlist link copied"); }
  catch { tell("Album link", `<a href="${esc(r.url)}" target="_blank" rel="noopener">${esc(r.url)}</a>`); }
}
async function renameAlbum() {
  const a = albums.find((x) => x.id === albumId);
  const name = await ask("Rename album", a?.name || "", "Album name");
  if (!name) return;
  await api(`/api/albums/${albumId}/rename`, { name });
  refresh();
}
async function deleteAlbum() {
  if (!await confirmBox("Delete album?", "Its YouTube playlist is deleted too. The videos stay.", "Delete", true)) return;
  await api(`/api/albums/${albumId}/delete`, {});
  await refresh();
  setView("albums");
  toast("Album deleted");
}
async function saveEvent() {
  const ev = currentEvent();
  if (!ev) return;
  const name = await ask("Save as album", ev.name, "Album name", "Save", "It becomes a normal album and a private YouTube playlist.");
  if (!name) return;
  const { id } = await api("/api/albums", { name });
  await api(`/api/albums/${id}/add`, { video_ids: ev.video_ids });
  await refresh();
  setView("photos", id);
}

// ---------- map ----------
let map = null, mapLayer = null, mapKey = "";
function showMap() {
  const located = videos.filter((v) => v.lat != null && v.lon != null);
  $("#mapEmpty").hidden = located.length > 0;
  if (!map) {
    map = L.map("map", { zoomControl: true, worldCopyJump: true }).setView([20.6, 78.9], 4);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(map);
  }
  setTimeout(() => map.invalidateSize(), 0);  // the map div was hidden until now
  const key = located.map((v) => v.id).join();
  if (key === mapKey) return;
  mapKey = key;
  if (mapLayer) map.removeLayer(mapLayer);
  mapLayer = L.markerClusterGroup({ showCoverageOnHover: false, maxClusterRadius: 50 });
  for (const v of located) {
    const ic = L.divIcon({ className: "", iconSize: [56, 56], iconAnchor: [28, 28],
      html: `<div class="map-thumb" style="background-image:url('/media/thumbs/${v.id}.jpg')"></div>` });
    const when = parseDate(v.recorded_at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
    const m = L.marker([v.lat, v.lon], { icon: ic });
    m.bindPopup(`<div class="map-pop" data-open="${v.id}"><img src="/media/thumbs/${v.id}.jpg" alt="">
      <div><b>${esc(v.place || "")}</b><br>${when}</div></div>`);
    mapLayer.addLayer(m);
  }
  map.addLayer(mapLayer);
  if (located.length) map.fitBounds(mapLayer.getBounds(), { padding: [40, 40], maxZoom: 14 });
}
$("#map").addEventListener("click", (e) => {
  const p = e.target.closest("[data-open]");
  if (p) openViewer(+p.dataset.open, videos.filter((v) => v.lat != null).map((v) => v.id));
});

// ---------- selecting ----------
function enterSelect() { selecting = true; document.body.classList.add("selecting"); updateSelBar(); }
function exitSelect() { selecting = false; selected.clear(); document.body.classList.remove("selecting"); updateSelBar(); lastRendered = ""; }
function updateSelBar() {
  $("#selBar").hidden = !selecting;
  $("#selCount").textContent = selected.size ? `${selected.size} selected` : "Select videos";
  ["#selAddAlbum", "#selRemoveAlbum", "#selDelete", "#selFav"].forEach((s) => { $(s).disabled = !selected.size; });
  $("#selRemoveAlbum").hidden = !albumId;
  const allFav = selected.size && [...selected].every((id) => videos.find((v) => v.id === id)?.favorite);
  setIcon($("#selFav"), allFav ? "favorite" : "favorite_border");
}
$("#selectBtn").onclick = () => { if (view !== "photos") setView("photos"); selecting ? exitSelect() : enterSelect(); render(); };
$("#selCancel").onclick = () => { exitSelect(); render(); };
$("#selAddAlbum").onclick = () => openPicker([...selected]);
$("#selDelete").onclick = () => openDelete([...selected]);
$("#selFav").onclick = async () => {
  const ids = [...selected];
  const allFav = ids.every((id) => videos.find((v) => v.id === id)?.favorite);
  await Promise.all(ids.map((id) => api(`/api/videos/${id}/favorite`, { favorite: !allFav })));
  exitSelect(); refresh();
  toast(allFav ? `Removed ${plural(ids.length, "video")} from Favourites` : `Added ${plural(ids.length, "video")} to Favourites`);
};
$("#selRemoveAlbum").onclick = async () => {
  const n = selected.size;
  await api(`/api/albums/${albumId}/remove`, { video_ids: [...selected] });
  exitSelect(); refresh();
  toast(`Removed ${plural(n, "video")} from the album`);
};

let pressTimer = null, longPressed = false;
$("#gallery").addEventListener("pointerdown", (e) => {
  const t = e.target.closest(".tile");
  if (!t || selecting) return;
  longPressed = false;
  pressTimer = setTimeout(() => { longPressed = true; enterSelect(); toggleSel(+t.dataset.id); }, 450);
});
["pointerup", "pointerleave", "pointercancel"].forEach((ev) => $("#gallery").addEventListener(ev, () => clearTimeout(pressTimer), { passive: true }));
window.addEventListener("scroll", () => clearTimeout(pressTimer), { passive: true });
$("#gallery").addEventListener("contextmenu", (e) => { if (e.target.closest(".tile")) e.preventDefault(); });

function toggleSel(id, force) {
  const on = force ?? !selected.has(id);
  on ? selected.add(id) : selected.delete(id);
  const t = document.querySelector(`.tile[data-id="${id}"]`);
  if (t) { t.classList.toggle("selected", on); t.querySelector(".t-sel").innerHTML = icon(on ? "check_circle" : "check_circle_outline"); }
  updateSelBar();
}
$("#gallery").addEventListener("click", (e) => {
  const ds = e.target.closest(".day-sel");
  if (ds) {
    const ids = ds.dataset.day.split(",").map(Number);
    if (!selecting) enterSelect();
    const all = ids.every((id) => selected.has(id));
    ids.forEach((id) => toggleSel(id, !all));
    lastRendered = ""; render();
    return;
  }
  const t = e.target.closest(".tile");
  if (!t) return;
  if (longPressed) { longPressed = false; return; }
  if (selecting || e.target.closest(".t-sel")) { if (!selecting) enterSelect(); toggleSel(+t.dataset.id); }
  else openViewer(+t.dataset.id);
});

// ---------- album picker ----------
let pickerVideos = [];
function openPicker(videoIds) {
  pickerVideos = videoIds;
  $("#pickerTitle").textContent = videoIds.length > 1 ? `Add ${plural(videoIds.length, "video")} to` : "Add to album";
  $("#pickerName").value = "";
  renderPicker();
  $("#picker").hidden = false;
}
function renderPicker() {
  const single = pickerVideos.length === 1 ? videos.find((v) => v.id === pickerVideos[0]) : null;
  const inSet = new Set(single ? albumIds(single) : []);
  $("#pickerList").innerHTML = albums.length ? albums.map((a) => `
    <button class="picker-item${inSet.has(a.id) ? " in" : ""}" data-album="${a.id}">
      ${a.cover ? `<img src="/media/thumbs/${a.cover}.jpg" alt="">` : `<span class="ph-sq"></span>`}
      <span class="pi-text">${esc(a.name)}<br><span class="muted small">${plural(a.count, "video")}</span></span>
      ${inSet.has(a.id) ? `<span class="tick">${icon("check_circle")}</span>` : ""}</button>`).join("")
    : `<div class="muted">No albums yet. Name one above.</div>`;
}
$("#pickerList").addEventListener("click", async (e) => {
  const b = e.target.closest(".picker-item");
  if (!b) return;
  const aid = +b.dataset.album, isIn = b.classList.contains("in");
  const name = albums.find((a) => a.id === aid)?.name;
  await api(`/api/albums/${aid}/${isIn ? "remove" : "add"}`, { video_ids: pickerVideos });
  await refresh();
  if (pickerVideos.length > 1) { $("#picker").hidden = true; exitSelect(); render(); toast(`Added to ${name}`, { label: "View", run: () => setView("photos", aid) }); }
  else renderPicker();
});
$("#pickerNew").addEventListener("submit", async (e) => {
  e.preventDefault();
  const name = $("#pickerName").value.trim();
  if (!name) return $("#pickerName").focus();
  const { id } = await api("/api/albums", { name });
  if (pickerVideos.length) await api(`/api/albums/${id}/add`, { video_ids: pickerVideos });
  await refresh();
  $("#pickerName").value = "";
  if (pickerVideos.length > 1) { $("#picker").hidden = true; exitSelect(); render(); }
  else renderPicker();
  toast(`Added to ${name}`, { label: "View", run: () => { closeViewer(); setView("photos", id); } });
});

// ---------- free up space on the phone ----------
let freeHidden = false;
function renderFreeBar() {
  const list = videos.filter(onPhoneSafe);
  const bytes = list.reduce((s, v) => s + v.size, 0);
  const show = list.length > 0 && !freeHidden && view === "photos" && filter === "all" && !(albumId || eventId || personId);
  $("#freeBar").hidden = !show;
  if (!show) return;
  $("#freeTitle").textContent = `Free up ${fmtSize(bytes)} on your phone`;
  $("#freeSub").textContent = `${plural(list.length, "video")} are safely backed up to YouTube.`;
}
$("#freeHide").onclick = () => { freeHidden = true; renderFreeBar(); };
const freeList = (rows) => `<ul class="free-list">${rows.slice(0, 200).map((r) =>
  `<li><span>${esc(r.name)}</span><span>${parseDate(r.recorded_at).toLocaleDateString()} · ${fmtSize(r.size)}</span></li>`).join("")}
  ${rows.length > 200 ? `<li class="muted">…and ${rows.length - 200} more</li>` : ""}</ul>`;
async function openFreeSheet() {
  $("#freeSheet").hidden = false;
  $("#freeBody").innerHTML = '<p class="muted">Checking…</p>';
  let d;
  try { d = await api("/api/free-space"); } catch (e) { $("#freeBody").innerHTML = `<p class="note bad">${esc(e.message)}</p>`; return; }
  let html = "";
  if (d.synced.length) {
    html += `<section><p><b>${plural(d.synced.length, "video")} · ${fmtSize(d.synced_bytes)}</b> can be removed with one tap.</p>
      <p class="muted">They're deleted from your phone at the next Wi-Fi sync, and stay on YouTube in full quality and here as 720p copies.
      Each one is checked against YouTube again first.</p>
      ${freeList(d.synced)}<button class="btn filled" id="freeGo">${icon("delete_sweep")}Free up ${fmtSize(d.synced_bytes)}</button></section>`;
  }
  if (d.manual.length) {
    const how = d.has_phone_sync
      ? `<b>${plural(d.manual.length, "video")}</b> aren't synced yet. Once Syncthing copies them from your phone, they move up here.`
      : `<b>${plural(d.manual.length, "video")}</b> can't be deleted for you yet. A website isn't allowed to delete files on your phone, so this needs
         <b>Syncthing</b> (SETUP.md, section 5).`;
    html += `<section><p>${how}</p>${freeList(d.manual)}
      <p class="muted">Or delete them in your phone's Gallery yourself, then:</p>
      <button class="btn outline" id="freeMark">I've deleted these myself</button></section>`;
  }
  $("#freeBody").innerHTML = html || '<p class="muted">Nothing to free up right now.</p>';
  const go = $("#freeGo");
  if (go) go.onclick = async () => {
    go.disabled = true; go.textContent = "Checking with YouTube…";
    try {
      const r = await api("/api/free-space", {});
      $("#freeBody").innerHTML = `<p class="note good">${plural(r.freed, "video")} (${fmtSize(r.bytes)}) will be removed from your phone at the next Wi-Fi sync.</p>` +
        (r.kept.length ? `<p class="note bad">Kept ${r.kept.length} because YouTube's copy didn't check out: ${r.kept.map(esc).join(", ")}</p>` : "");
    } catch (e) { $("#freeBody").innerHTML = `<p class="note bad">${esc(e.message)}</p>`; }
    refresh();
  };
  const mark = $("#freeMark");
  if (mark) mark.onclick = async () => {
    if (!await confirmBox("Mark as deleted?", `Only do this after deleting these ${plural(d.manual.length, "video")} from your phone.`, "Mark as deleted")) return;
    await api("/api/free-space/mark", { video_ids: d.manual.map((r) => r.id) });
    await refresh(); openFreeSheet();
  };
}
$("#freeBtn").onclick = openFreeSheet;

// ---------- delete forever ----------
const delTemplate = $("#delBody").innerHTML;
let delIds = [];
function openDelete(ids) {
  delIds = ids;
  const n = ids.length;
  $("#delTitle").textContent = n === 1 ? "Delete forever?" : `Delete ${n} videos forever?`;
  $("#delBody").innerHTML = delTemplate;
  $("#delBody").querySelectorAll("[data-close]").forEach((b) => b.onclick = () => { $("#delSheet").hidden = true; });
  $("#delGo").onclick = runDelete;
  $("#delSheet").hidden = false;
}
async function runDelete() {
  const go = $("#delGo");
  go.disabled = true; go.textContent = "Deleting…";
  try {
    const r = await api("/api/videos/delete", { video_ids: delIds, delete_copies: $("#delCopies").checked });
    $("#delSheet").hidden = true;
    if (openId && delIds.includes(openId)) closeViewer();
    exitSelect();
    if (r.skipped.length) tell(`Deleted ${r.deleted}, kept ${r.skipped.length}`, r.skipped.map(esc).join("<br>"));
    else toast(`${plural(r.deleted, "video")} deleted forever`);
  } catch (e) {
    $("#delSheet").hidden = true;
    tell("Couldn't delete", esc(e.message));
  }
  refresh();
}

// ---------- viewer ----------
let viewerList = [];   // ids in the order shown when the viewer was opened (for swiping)
function openViewer(id, list) {
  viewerList = list || (view === "photos" ? visible().map((v) => v.id) : videos.map((v) => v.id));
  if (!viewerList.includes(id)) viewerList = videos.map((v) => v.id);
  $("#viewer").hidden = false;
  document.body.classList.add("viewing");
  if (window.innerWidth > 1200 && localStorage.getItem("fv-info") === "1") $("#viewer").classList.add("info-open");
  showInViewer(id);
}
function showInViewer(id) {
  openId = id;
  const v = videos.find((x) => x.id === id);
  if (!v) return closeViewer();
  const player = $("#player"), frame = $("#ytFrame");
  player.pause();
  const ytOnly = v.status === "youtube";
  const ready = !ytOnly && v.status !== "new";
  player.hidden = !ready;
  frame.hidden = !ytOnly;
  $("#noPreview").hidden = ready || ytOnly;
  if (ready) { player.poster = `/media/thumbs/${id}.jpg`; player.src = `/media/previews/${id}.mp4`; player.play().catch(() => {}); }
  else { player.removeAttribute("src"); player.load(); }
  // Videos uploaded straight to YouTube have no copy on the PC, so they play from YouTube.
  const ytSrc = ytOnly ? `https://www.youtube.com/embed/${encodeURIComponent(v.youtube_id)}?autoplay=1&playsinline=1&rel=0` : "about:blank";
  if (frame.src !== ytSrc) frame.src = ytSrc;
  renderDetails();
}
function renderViewerChrome() {
  const v = videos.find((x) => x.id === openId);
  if (!v) return;
  const d = parseDate(v.recorded_at);
  $("#vDate").textContent = dayLabel(d);
  $("#vSub").textContent = [d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }), v.place].filter(Boolean).join(" · ");
  for (const a of [$("#vYt"), $("#vYtM")]) {
    a.hidden = !v.youtube_id;
    if (v.youtube_id) a.href = `https://www.youtube.com/watch?v=${encodeURIComponent(v.youtube_id)}`;
  }
  $("#vFav").classList.toggle("on", !!v.favorite);
  setIcon($("#vFavIcon"), v.favorite ? "favorite" : "favorite_border");
  const i = viewerList.indexOf(openId);
  $("#vPrev").disabled = i <= 0;
  $("#vNext").disabled = i < 0 || i >= viewerList.length - 1;
  $("#vDelete").disabled = v.status === "uploading";
}
function stepViewer(dir) {
  const i = viewerList.indexOf(openId) + dir;
  if (i < 0 || i >= viewerList.length) return;
  $("#viewer").classList.add("swipe-anim");
  showInViewer(viewerList[i]);
  setTimeout(() => $("#viewer").classList.remove("swipe-anim"), 200);
}
function closeViewer() {
  openId = null;
  const p = $("#player"); p.pause(); p.removeAttribute("src"); p.load();
  $("#ytFrame").src = "about:blank";
  $("#viewer").hidden = true;
  $("#viewer").classList.remove("info-open");
  document.body.classList.remove("viewing");
}
function toggleInfo(force) {
  const on = $("#viewer").classList.toggle("info-open", force);
  try { localStorage.setItem("fv-info", on ? "1" : "0"); } catch {}
  renderDetails();
}
$("#viewerClose").onclick = closeViewer;
$("#vPrev").onclick = () => stepViewer(-1);
$("#vNext").onclick = () => stepViewer(1);
$("#vInfo").onclick = () => toggleInfo();
$("#vAlbum").onclick = () => openId && openPicker([openId]);
$("#vDelete").onclick = () => openId && openDelete([openId]);
$("#vFav").onclick = async () => {
  const v = videos.find((x) => x.id === openId);
  if (!v) return;
  v.favorite = v.favorite ? 0 : 1;   // instant feedback; the next refresh confirms
  renderViewerChrome();
  await api(`/api/videos/${v.id}/favorite`, { favorite: !!v.favorite });
  refresh();
};
document.addEventListener("keydown", (e) => {
  if (!$("#viewer").hidden && !e.target.closest("input, textarea") && $("#dialog").hidden) {
    if (e.key === "ArrowLeft") return stepViewer(-1);
    if (e.key === "ArrowRight") return stepViewer(1);
    if (e.key === "i") return toggleInfo();
  }
  if (e.key !== "Escape") return;
  if (dialogCancel) dialogCancel();
  else if (!$("#uploadMenu").hidden) $("#uploadMenu").hidden = true;
  else if (!$("#peoplePicker").hidden) $("#peoplePicker").hidden = true;
  else if (!$("#picker").hidden) $("#picker").hidden = true;
  else if (!$("#delSheet").hidden) $("#delSheet").hidden = true;
  else if (!$("#freeSheet").hidden) $("#freeSheet").hidden = true;
  else if ($("#viewer").classList.contains("info-open") && !$("#viewer").hidden) toggleInfo(false);
  else if (!$("#viewer").hidden) closeViewer();
  else if (!$("#settings").hidden) $("#settings").hidden = true;
  else if (selecting) { exitSelect(); render(); }
});

// Swipe left/right for next/previous, down to close, up for info (like Google Photos).
(() => {
  const stage = $("#stage");
  let x0 = null, y0 = 0, t0 = 0, onControls = false;
  stage.addEventListener("touchstart", (e) => {
    if (e.touches.length !== 1) { x0 = null; return; }
    const t = e.touches[0], r = $("#player").getBoundingClientRect();
    // Don't steal the video's own scrub bar at the bottom of the player.
    onControls = !$("#player").hidden && t.clientY > r.bottom - 56 && t.clientY < r.bottom + 8;
    x0 = t.clientX; y0 = t.clientY; t0 = Date.now();
  }, { passive: true });
  stage.addEventListener("touchend", (e) => {
    if (x0 === null || onControls) return;
    const t = e.changedTouches[0], dx = t.clientX - x0, dy = t.clientY - y0;
    x0 = null;
    if (Date.now() - t0 > 800) return;
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) stepViewer(dx < 0 ? 1 : -1);
    else if (dy > 110 && Math.abs(dy) > Math.abs(dx) * 1.5) closeViewer();
    else if (dy < -90 && Math.abs(dy) > Math.abs(dx) * 1.5) toggleInfo(true);
  }, { passive: true });
})();

function renderDetails() {
  const v = videos.find((x) => x.id === openId);
  if (!v) return closeViewer();
  renderViewerChrome();
  if (!$("#viewer").classList.contains("info-open")) return;
  const d = parseDate(v.recorded_at);
  let note = "";
  if (onPhoneSafe(v))
    note = `<div class="note good">Safely backed up. You can delete it from your phone${v.synced ? ", or use <b>Free up space</b>" : ""}.</div>`;
  else if (v.source === "phone" && !PROBLEM.has(v.status) && !ON_YT.has(v.status))
    note = `<div class="note">Keep this video on your phone until it's backed up.</div>`;
  else if (v.status === "youtube")
    note = `<div class="note">Uploaded to YouTube directly, so it plays from YouTube${v.privacy === "private" ? " (private videos may only play via ↗ at the top)" : ""}.
      <b>Import YouTube Takeout</b> in Settings gives it a copy here.</div>`;
  if (v.error) note += `<div class="note bad">${esc(v.error)}</div>`;

  const chips = albumIds(v).map((id) => albums.find((a) => a.id === id)).filter(Boolean)
    .map((a) => `<button class="chip" data-goto="${a.id}">${icon("collections")}${esc(a.name)}</button>`).join("");
  const seen = new Set();
  // Only people listed in People (named or in 2+ clips); ✕ = "not them" for this one face.
  const faceChips = videoFaces(v).filter(([, pid]) => people.some((p) => p.id === pid) && !seen.has(pid) && seen.add(pid))
    .map(([fid, pid]) => `<span class="face-wrap"><button class="face-chip" data-person="${pid}"><img src="/media/faces/${fid}.jpg" alt="">${esc(personName(pid) || "Add name")}</button>
      <button class="not-them" data-notme="${fid}" title="Not ${esc(personName(pid) || "this person")}" aria-label="Not this person">${icon("close")}</button></span>`).join("");
  const tz = -d.getTimezoneOffset() / 60;
  const meta = [v.width ? `${v.width} × ${v.height}` : "", v.size ? fmtSize(v.size) : "", fmtDur(v.duration)].filter(Boolean).join(" · ");
  const from = v.source === "phone" ? ["smartphone", "From your phone"] : v.source === "youtube" ? ["play_circle", "Uploaded to YouTube directly"] : ["computer", "From this PC"];
  const backed = ON_YT.has(v.status) ? "cloud_done" : PROBLEM.has(v.status) ? "error" : "cloud_upload";

  $("#details").innerHTML = `<div class="d-head"><button class="ibtn" id="dClose" aria-label="Close info">${icon("close")}</button><h2>Info</h2></div>
    ${note}
    ${faceChips ? `<div class="d-sec">People</div><div class="faces-row">${faceChips}</div>` : ""}
    <div class="d-sec">Details</div>
    <div class="d-row">${icon("event")}<div><b>${d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" })}</b>
      <span>${d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })} · GMT${tz >= 0 ? "+" : ""}${tz}</span></div></div>
    <div class="d-row">${icon("movie")}<div><b>${esc(v.name)}</b><span>${meta}</span></div></div>
    ${v.place ? `<div class="d-row">${icon("place")}<div><b>${esc(v.place)}</b><span>${v.lat.toFixed(4)}, ${v.lon.toFixed(4)}</span></div></div>` : ""}
    <div class="d-row">${icon(backed)}<div><b>${esc(statusText(v))}</b>${v.privacy ? `<span>YouTube: ${esc(v.privacy)}</span>` : v.youtube_id ? "<span>YouTube: private</span>" : ""}</div></div>
    <div class="d-row">${icon(from[0])}<div><b>${from[1]}</b></div></div>
    <div class="d-sec">Albums</div>
    <div class="chips">${chips}<button class="chip" id="addToAlbum">${icon("add")}Add to album</button></div>
    <div class="row">
      ${v.youtube_id ? `<a class="btn outline" target="_blank" rel="noopener" href="https://studio.youtube.com/video/${esc(v.youtube_id)}/edit">${icon("open_in_new")}YouTube Studio</a>` : ""}
      ${PROBLEM.has(v.status) ? `<button class="btn filled" id="retryBtn">${icon("refresh")}${v.status === "hold" ? "Check again" : "Retry"}</button>` : ""}
    </div>
    ${v.status !== "youtube" ? '<p class="muted small" style="margin-top:16px">Plays a 720p copy kept on your PC. ↗ at the top opens full quality on YouTube.</p>' : ""}`;
  $("#dClose").onclick = () => toggleInfo(false);
  $("#addToAlbum").onclick = () => openPicker([v.id]);
  $("#details").querySelectorAll("[data-goto]").forEach((b) => b.onclick = () => { closeViewer(); setView("photos", +b.dataset.goto); });
  $("#details").querySelectorAll("[data-person]").forEach((b) => b.onclick = () => { closeViewer(); setView("photos", null, null, +b.dataset.person); });
  $("#details").querySelectorAll("[data-notme]").forEach((b) => b.onclick = async () => {
    b.disabled = true;
    await api(`/api/faces/${b.dataset.notme}/remove`, {});
    refresh();
    toast("Got it, removed from that person");
  });
  const rb = $("#retryBtn");
  if (rb) rb.onclick = async () => { await api(`/api/videos/${v.id}/retry`, {}); refresh(); toast("Trying again"); };
}

// ---------- people ----------
const personCard = (p, attr = `data-pid="${p.id}"`) => `<button class="person" ${attr}>
  ${p.cover_face ? `<img loading="lazy" src="/media/faces/${p.cover_face}.jpg" alt="">` : '<span class="ph-face"></span>'}
  <div class="pname${p.name ? "" : " unnamed"}">${esc(p.name || "Add name")}</div><div class="pcount">${plural(p.videos, "clip")}</div></button>`;
function renderPeople() {
  const s = peopleInfo.scan || {};
  const missing = s.models === "missing";
  $("#peopleBlock").hidden = !people.length && !s.running && !missing;
  $("#peopleNote").textContent = missing ? "Face models download automatically when the PC is online"
    : s.running ? `Finding faces… ${s.done}/${s.total} clips` : "Found on this PC, nothing is uploaded";
  $("#peopleRow").innerHTML = people.map((p) => personCard(p)).join("");
}
$("#peopleRow").addEventListener("click", (e) => {
  const b = e.target.closest("[data-pid]");
  if (b) setView("photos", null, null, +b.dataset.pid);
});
async function renderPersonFaces() {
  const box = $("#personFaces");
  if (!personId) { box.hidden = true; return; }
  const list = await api(`/api/people/${personId}/faces`);
  box.hidden = list.length < 2;
  box.innerHTML = list.slice(0, 60).map((f) => `<div class="pf"><img src="/media/faces/${f.id}.jpg" alt="">
    <button data-notme="${f.id}" title="Not this person" aria-label="Not this person">${icon("close")}</button></div>`).join("");
}
$("#personFaces").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-notme]");
  if (!b) return;
  await api(`/api/faces/${b.dataset.notme}/remove`, {});
  await refresh(); renderPersonFaces();
  toast("Removed from this person");
});
async function namePerson() {
  const p = currentPerson();
  const name = await ask(p?.name ? "Rename" : "Who is this?", p?.name || "", "Name");
  if (name === null) return;
  await api(`/api/people/${personId}/name`, { name });
  refresh();
}
async function hidePerson() {
  if (!await confirmBox("Hide this person?", "They won't show in People. Their clips stay.", "Hide")) return;
  await api(`/api/people/${personId}/hide`, {});
  await refresh(); setView("albums");
}
function mergePerson() {
  $("#peoplePickerList").innerHTML = people.filter((p) => p.id !== personId).map((p) => personCard(p, `data-into="${p.id}"`)).join("")
    || '<p class="muted">No one else yet.</p>';
  $("#peoplePicker").hidden = false;
}
$("#peoplePickerList").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-into]");
  if (!b) return;
  const into = +b.dataset.into;
  await api(`/api/people/${personId}/merge`, { into });
  $("#peoplePicker").hidden = true;
  await refresh(); setView("photos", null, null, into);
  toast("Combined");
});

// ---------- upload (sending files from this device) ----------
const sendQueue = [];
let sending = false;
let sendStats = { done: 0, dup: 0, failed: 0 };
$("#uploadBtn").onclick = (e) => {
  e.stopPropagation();
  if (!status.local) return $("#fileInput").click();  // phone: straight to the gallery picker
  $("#uploadMenu").hidden = !$("#uploadMenu").hidden;
};
document.addEventListener("click", (e) => { if (!e.target.closest(".upload-wrap")) $("#uploadMenu").hidden = true; });
$("#uploadDevice").onclick = () => { $("#uploadMenu").hidden = true; $("#fileInput").click(); };
$("#fileInput").addEventListener("change", (e) => {
  sendQueue.push(...e.target.files);
  e.target.value = "";
  pumpSend();
});
async function pumpSend() {
  if (sending) return;
  sending = true;
  while (sendQueue.length) {
    const f = sendQueue[0];
    try {
      const res = await sendFile(f, sendQueue.length);
      if (res.duplicate) sendStats.dup++; else sendStats.done++;
    } catch (err) {
      sendStats.failed++;
      console.error(err);
    }
    sendQueue.shift();
    refresh();
  }
  sending = false;
  const s = sendStats;
  $("#sendQueue").hidden = true;
  toast(`Added ${plural(s.done, "video")}` + (s.dup ? ` · ${s.dup} already here` : "") + (s.failed ? ` · ${s.failed} failed` : ""));
  sendStats = { done: 0, dup: 0, failed: 0 };
}
function sendFile(file, remaining) {
  return new Promise((resolve, reject) => {
    const box = $("#sendQueue");
    box.hidden = false;
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", `/api/upload?name=${encodeURIComponent(file.name)}&modified=${file.lastModified || 0}`);
    xhr.upload.onprogress = (e) => {
      const pct = e.lengthComputable ? (e.loaded / e.total) * 100 : 0;
      box.innerHTML = `<div>Sending ${esc(file.name)} to the PC${remaining > 1 ? ` · ${remaining - 1} more` : ""}</div>
        <div class="bar"><i style="width:${pct}%"></i></div><div class="small" style="opacity:.7">Keep this page open</div>`;
    };
    xhr.onload = () => xhr.status < 300 ? resolve(JSON.parse(xhr.responseText)) : reject(new Error(xhr.responseText));
    xhr.onerror = () => reject(new Error("Network error"));
    xhr.send(file);
  });
}
window.addEventListener("beforeunload", (e) => { if (sending) { e.preventDefault(); e.returnValue = ""; } });

// ---------- PC-only actions ----------
async function importFolder() {
  $("#uploadMenu").hidden = true;
  const folder = await ask("Import a folder", "", "e.g. D:\\Videos\\2024", "Import",
    "Every video in it (and its subfolders) is added and backed up. Originals go to the Recycle Bin once they're safely on YouTube.");
  if (!folder) return;
  try { await api("/api/import", { folder }); toast("Importing…"); } catch (e) { tell("Couldn't import", esc(e.message)); }
  refresh();
}
$("#importBtn").onclick = importFolder;
$("#importBtn2").onclick = importFolder;

function openSettings() { $("#settings").hidden = false; $("#deleteDays").value = status.delete_after_days; renderSettings(); renderPush(); }
$("#settingsBtn").onclick = openSettings;
$$("[data-close]").forEach((b) => b.onclick = () => { $("#" + b.dataset.close).hidden = true; });
$$(".sheet").forEach((s) => s.addEventListener("click", (e) => {
  if (e.target !== s) return;
  if (s.id === "dialog") { if (dialogCancel) dialogCancel(); } else s.hidden = true;
}));
$("#connectBtn").onclick = connect;
$("#disconnectBtn").onclick = async () => {
  if (!await confirmBox("Disconnect YouTube?", "Uploads pause until you connect again.", "Disconnect", true)) return;
  await api("/api/youtube/logout", {}); refresh();
};
$("#saveDays").onclick = async () => { await api("/api/settings", { delete_after_days: +$("#deleteDays").value }); refresh(); toast("Saved"); };

async function addWatch(source) {
  const folder = await ask(source === "phone" ? "Phone sync folder" : "Watch a PC folder", "", "e.g. C:\\Users\\you\\Videos\\Phone", "Add",
    source === "phone" ? "The folder on this PC that Syncthing copies your phone's videos into." : "New videos added to this folder are backed up automatically.");
  if (!folder) return;
  try { await api("/api/watch", { folder, source }); } catch (e) { return tell("Couldn't add folder", esc(e.message)); }
  await refresh(); renderSettings();
}
$("#addWatchPhone").onclick = () => addWatch("phone");
$("#addWatchPc").onclick = () => addWatch("pc");
$("#watchList").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-unwatch]");
  if (!b || !await confirmBox("Stop watching this folder?", "Videos already added stay in the vault.", "Stop watching")) return;
  await api("/api/watch/remove", { folder: b.dataset.unwatch });
  await refresh(); renderSettings();
});
$("#takeoutBtn").onclick = async () => {
  const folder = await ask("Import YouTube Takeout", "", "Path of the unzipped Takeout folder", "Import",
    "Paste the path of the unzipped folder (the one that contains <b>Takeout</b>).");
  if (!folder) return;
  try { await api("/api/takeout", { folder }); } catch (e) { return tell("Couldn't import", esc(e.message)); }
  refresh();
};
$("#ytSyncBtn").onclick = async () => {
  await api("/api/youtube/import", {});
  $("#ytSyncState").textContent = "Syncing…";
  setTimeout(refresh, 8000);
};

async function connect() {
  // Open the tab first (synchronously) so phones don't block it as a popup.
  const tab = window.open("about:blank", "_blank");
  let url;
  try { ({ url } = await api("/api/youtube/login", {})); } catch (e) { tab?.close(); return tell("Can't connect yet", esc(e.message)); }
  if (tab) tab.location = url; else location.href = url;
  if (!status.local) {
    // Google can only return to the PC's own address, so on the phone the user pastes it back.
    openSettings();
    $("#pasteBox").hidden = false;
    $("#pasteUrl").value = "";
  }
}
$("#pasteBtn").onclick = async () => {
  try { $("#pasteUrl").value = await navigator.clipboard.readText(); } catch { $("#pasteUrl").focus(); }
};
$("#pasteForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  try { await api("/api/youtube/finish", { url: $("#pasteUrl").value }); }
  catch (err) { return tell("Didn't work", esc(err.message)); }
  $("#pasteBox").hidden = true;
  channelName = null;
  await refresh();
  renderSettings();
  toast("YouTube connected");
});

let channelName = null;
async function renderSettings() {
  $("#lanUrl").textContent = status.lan_url || "";
  $("#pin").textContent = status.pin || "";
  $("#connectBtn").hidden = status.connected;
  $("#connectBtn").textContent = status.auth === "none" ? "Connect YouTube" : "Reconnect YouTube";
  $("#disconnectBtn").hidden = !status.connected;
  if (status.connected && channelName === null) {
    channelName = (await api("/api/channel").catch(() => ({}))).title || "";
  }
  $("#ytName").textContent = status.connected ? (channelName || "YouTube channel") : "YouTube";
  $("#ytAvatar").textContent = (channelName || "Y").trim().charAt(0).toUpperCase();
  $("#ytState").textContent = !status.has_client_secret ? "Not set up yet: client_secret.json is missing (see SETUP.md)."
    : status.connected ? "Connected · uploads are private"
    : status.auth === "scope" ? "Reconnect needed: albums need one extra permission."
    : status.auth === "expired" ? "Google sign-in ran out. Reconnect to continue uploading."
    : status.auth === "offline" ? "Offline right now."
    : "Not connected";

  const yi = status.yt_import;
  if ($("#ytSyncState").textContent !== "Syncing…" || yi)
    $("#ytSyncState").textContent = yi ? `Last sync ${yi.at.slice(11, 16)}: ${plural(yi.added, "new video")}, ${plural(yi.albums, "new album")}` : "";
  const n = status.youtube_only || 0;
  $("#takeoutIntro").textContent = n
    ? `${plural(n, "video")} you uploaded to YouTube directly only play from YouTube (private ones often won't play here). Give them a copy here in 4 steps:`
    : "All your videos have a copy here. If you upload to YouTube directly again, repeat this.";
  const to = status.takeout;
  $("#takeoutState").innerHTML = !to ? "" : to.running
    ? `<p class="note">Matching files… ${to.found} checked, ${to.matched} matched</p>`
    : `<p class="note ${to.matched ? "good" : ""}">Takeout: ${to.matched} matched` +
      (to.already ? `, ${to.already} already had a copy` : "") +
      (to.unmatched.length ? `, ${to.unmatched.length} not matched (${to.unmatched.slice(0, 5).map(esc).join(", ")}${to.unmatched.length > 5 ? "…" : ""})` : "") +
      `${to.matched ? ". Previews are being made now." : "."}</p>`;
  const ts = status.tailscale;
  $("#tsBlock").innerHTML = !ts ? `<div class="set-row">${icon("link")}<div><b>From anywhere</b><div class="muted">Tailscale isn't running on this PC (see SETUP.md).</div></div></div>`
    : ts.https ? `<div class="set-row">${icon("link")}<div><b>From anywhere (Tailscale)</b><div class="muted">Open in Chrome on your phone, then <b>⋮ → Install app</b>:</div><div class="code">${esc(ts.url)}</div></div></div>`
    : `<div class="set-row">${icon("link")}<div><b>From anywhere</b><div class="muted">Tailscale is on, but HTTPS certificates are off. Turn them on to install the app and get notifications.</div></div></div>`;
  const wf = status.watch_folders || [];
  $("#watchList").innerHTML = wf.length ? wf.map((f) => `<div class="watch-item"><span>${icon(f.source === "phone" ? "smartphone" : "computer")}</span>
    <span class="p">${esc(f.path)}</span>
    <button class="ibtn small" data-unwatch="${esc(f.path)}" title="Stop watching" aria-label="Stop watching">${icon("close")}</button></div>`).join("")
    : `<p class="muted">No folders yet.</p>`;
}

// ---------- notifications + install ----------
const pushSupported = "serviceWorker" in navigator && "PushManager" in window && window.isSecureContext;
let swReg = null;
if ("serviceWorker" in navigator && window.isSecureContext) {
  navigator.serviceWorker.register("/sw.js").then((r) => { swReg = r; }).catch((e) => console.warn("SW", e));
}
const b64ToBytes = (s) => {
  const raw = atob((s + "=".repeat((4 - (s.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
};
async function currentSub() {
  if (!pushSupported) return null;
  const reg = swReg || await navigator.serviceWorker.ready;
  return reg.pushManager.getSubscription();
}
async function renderPush() {
  const st = $("#pushState");
  ["#pushOn", "#pushOff", "#pushTest"].forEach((s) => { $(s).hidden = true; });
  if (!pushSupported) { st.textContent = "Notifications need the secure Tailscale address (https://…). Open Footage Vault from that address on your phone."; return; }
  if (Notification.permission === "denied") { st.textContent = "Notifications are blocked for this site. Allow them in the browser's site settings."; return; }
  const sub = await currentSub().catch(() => null);
  if (sub) {
    st.textContent = "On. You'll hear when videos are safely backed up, or if something needs attention.";
    $("#pushOff").hidden = $("#pushTest").hidden = false;
  } else {
    st.textContent = "Get notified when videos are safely backed up (so you can delete them from your phone) or if something needs attention.";
    $("#pushOn").hidden = false;
  }
}
$("#pushOn").onclick = async () => {
  try {
    if (await Notification.requestPermission() !== "granted") return renderPush();
    const reg = swReg || await navigator.serviceWorker.ready;
    const { key } = await api("/api/push/key");
    const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(key) });
    await api("/api/push/subscribe", sub.toJSON());
    toast("Notifications on");
  } catch (e) { tell("Couldn't turn on notifications", esc(e.message)); }
  renderPush();
};
$("#pushOff").onclick = async () => {
  const sub = await currentSub();
  if (sub) { await api("/api/push/unsubscribe", { endpoint: sub.endpoint }); await sub.unsubscribe(); }
  renderPush();
};
$("#pushTest").onclick = async () => {
  const { sent } = await api("/api/push/test", {});
  sent ? toast("Test sent") : tell("No device got it", "Try turning notifications off and on again.");
};
let installPrompt = null;
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installPrompt = e; renderNotices(); });
window.addEventListener("appinstalled", () => { installPrompt = null; renderNotices(); });

// ---------- status pill + notice cards ----------
function renderPill() {
  const p = $("#statusPill");
  const c = status.counts || {};
  const pending = (c.new || 0) + (c.queued || 0) + (c.uploading || 0);
  const problems = (c.failed || 0) + (c.hold || 0);
  const up = videos.find((v) => v.status === "uploading");
  let cls = "", ic = "cloud_done", text = "Backed up", long = "", act = null;
  if (!status.has_client_secret) { cls = "warn"; ic = "cloud_off"; text = "Set up"; long = " YouTube"; act = openSettings; }
  else if (!status.connected && status.auth === "offline") { ic = "cloud_off"; text = "Offline"; }
  else if (!status.connected) { cls = "warn"; ic = "cloud_off"; text = status.auth === "none" ? "Connect" : "Reconnect"; long = " YouTube"; act = connect; }
  else if (pending && status.user_paused) { ic = "pause"; text = `Paused`; long = ` · ${pending} left`; }
  else if (pending && status.paused_until) { cls = "warn"; ic = "schedule"; text = "Daily limit"; long = ` · resumes ${status.paused_until.slice(-5)}`; }
  else if (pending) { cls = "busy"; ic = null; text = `${pending} left`; long = ""; act = () => setFilter("progress"); }
  else if (problems) { cls = "bad"; ic = "error"; text = `${problems} problem${problems === 1 ? "" : "s"}`; act = () => setFilter("problems"); }
  else { cls = "ok"; }
  p.className = "pill " + cls;
  p.innerHTML = (ic ? icon(ic) : ring(up ? up.progress : 0.1)) + `<span>${text}</span><span class="lbl-long">${long}</span>`;
  p.title = pending ? `Backing up ${plural(pending, "video")}` : text + long;
  p.onclick = act || openSettings;
  p.hidden = false;
  $("#railFoot").textContent = status.connected && !pending ? `${plural(videos.length, "video")} backed up` : "";
}
let importDismissed = "";
let installDismissed = false;
function notice(cls, ic, title, text, btns = "") {
  return `<div class="card ${cls}"><span class="card-icon">${icon(ic)}</span><div class="card-text"><b>${title}</b>${text ? `<div>${text}</div>` : ""}</div>${btns}</div>`;
}
function renderNotices() {
  const box = $("#notices");
  let html = "";
  const imp = status.import;
  if (status.local && !status.has_client_secret) {
    html += notice("warn", "cloud_off", "Finish setting up YouTube", "Add your Google key (client_secret.json) as described in SETUP.md, then connect.");
  } else if (!status.connected && status.auth !== "offline") {
    const why = status.auth === "scope" ? "Albums need one extra permission. Uploads are paused until then."
      : status.auth === "expired" ? "Google signs you out every 7 days while the project is in Testing mode. Uploads are paused."
      : "Videos are kept and upload as soon as it's connected.";
    html += notice("warn", "cloud_off", status.auth === "none" ? "Connect YouTube to start backing up" : "Reconnect YouTube", why,
      `<button class="btn filled" id="bannerConnect">${status.auth === "none" ? "Connect" : "Reconnect"}</button>`);
  } else if (status.paused_until) {
    html += notice("", "schedule", "YouTube's daily upload limit reached", `Uploads continue by themselves at ${esc(status.paused_until.slice(-5))}.`);
  }
  if (imp && imp.running) {
    html += notice("", "folder", `Importing ${esc(imp.folder)}`, `${imp.found} found · ${imp.added} added<div class="bar"><i style="width:${imp.found ? Math.min(100, (imp.added + imp.skipped) / imp.found * 100) : 5}%"></i></div>`);
  } else if (imp && status.local && importDismissed !== imp.folder + imp.found) {
    html += notice("", "check_circle", "Import finished", `${imp.added} added · ${imp.skipped} already here${imp.errors ? ` · ${imp.errors} unreadable` : ""}`,
      `<button class="btn text" id="importOk">OK</button>`);
  }
  if (installPrompt && !installDismissed) {
    html += notice("", "smartphone", "Install Footage Vault", "Get it as an app on your home screen, with notifications.",
      `<button class="btn text" id="installNo">Not now</button><button class="btn filled" id="installBtn">Install</button>`);
  }
  if (box.innerHTML !== html) box.innerHTML = html;
  const bc = $("#bannerConnect"); if (bc) bc.onclick = connect;
  const ok = $("#importOk"); if (ok) ok.onclick = () => { importDismissed = imp.folder + imp.found; renderNotices(); };
  const ib = $("#installBtn"); if (ib) ib.onclick = async () => { installPrompt.prompt(); await installPrompt.userChoice; installPrompt = null; renderNotices(); };
  const inb = $("#installNo"); if (inb) inb.onclick = () => { installDismissed = true; renderNotices(); };
}

// ---------- polling ----------
async function refresh() {
  try {
    const [v, a, s, ev, pp] = await Promise.all([fetch("/api/videos"), fetch("/api/albums"), fetch("/api/status"),
      fetch("/api/events"), fetch("/api/people")]);
    if (v.status === 401) return location.reload();
    videos = await v.json();
    albums = await a.json();
    events = await ev.json();
    peopleInfo = await pp.json();
    people = peopleInfo.people || [];
    status = await s.json();
    document.body.classList.toggle("remote", !status.local);
    render();
    renderPill();
    renderNotices();
    if (selecting) updateSelBar();
    if (!$("#settings").hidden) renderSettings();
  } catch (e) { /* PC asleep or offline; try again next tick */ }
}
hydrateIcons();
setView("photos");
refresh();
setInterval(refresh, 3000);
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });

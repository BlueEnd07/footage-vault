const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const api = async (url, body) => {
  const r = await fetch(url, body === undefined ? undefined
    : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.detail || r.statusText);
  return data;
};

let videos = [];
let albums = [];
let status = {};
let view = "photos";          // photos | albums
let albumId = null;           // album being viewed in the photos view
let filter = new URLSearchParams(location.search).get("filter") || "all";
let selecting = false;
const selected = new Set();
let lastRendered = "";
let openId = null;

const IN_PROGRESS = new Set(["new", "queued", "uploading", "uploaded"]);
const ON_YT = new Set(["on_youtube", "done", "youtube"]);
const PROBLEM = new Set(["failed", "hold"]);
const onPhoneSafe = (v) => v.source === "phone" && ON_YT.has(v.status) && !v.phone_cleared_at;

// ---------- formatting ----------
const fmtDur = (s) => {
  s = Math.round(s || 0);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(sec).padStart(2, "0");
};
const fmtSize = (b) => b > 1e9 ? (b / 1e9).toFixed(2) + " GB" : (b / 1e6).toFixed(b < 1e7 ? 1 : 0) + " MB";
const parseDate = (s) => new Date(s.replace(" ", "T"));
const fmtDay = (d) => d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
const fmtMonth = (d) => d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
const albumIds = (v) => (v.albums ? String(v.albums).split(",").map(Number) : []);

function statusText(v) {
  switch (v.status) {
    case "new": return "Preparing preview";
    case "queued": return status.connected ? (status.user_paused ? "Waiting — uploads paused" : "Waiting to upload") : "Waiting — YouTube not connected";
    case "uploading": return `Uploading to YouTube · ${Math.round(v.progress * 100)}%`;
    case "uploaded": return "Uploaded · YouTube is processing";
    case "on_youtube": {
      const when = v.delete_after ? parseDate(v.delete_after).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
      return v.source === "pc" ? `On YouTube · original goes to Recycle Bin ${when}` : `On YouTube · PC copy cleared ${when}`;
    }
    case "done": return "On YouTube · original deleted";
    case "hold": return "On YouTube · original kept (check failed)";
    case "failed": return "Failed";
    case "youtube": return `On YouTube (${v.privacy || "uploaded directly"})`;
  }
  return v.status;
}

// ---------- views ----------
let events = [];
let eventId = null;           // auto event being viewed in the photos view
function setView(v, aid = null, eid = null, pid = null) {
  view = v;
  albumId = aid;
  eventId = eid;
  personId = pid;
  exitSelect();
  const sub = aid || eid || pid;
  const tab = v === "photos" && sub ? "albums" : v;
  document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t.dataset.view === tab));
  $("#photosView").hidden = v !== "photos";
  $("#albumsView").hidden = v !== "albums";
  $("#mapView").hidden = v !== "map";
  $("#albumHeader").hidden = !sub;
  $("#renameAlbum").hidden = $("#deleteAlbum").hidden = !aid;
  $("#saveEvent").hidden = !eid;
  $("#namePerson").hidden = $("#mergePerson").hidden = $("#hidePerson").hidden = !pid;
  $("#filters").hidden = !!sub;
  renderPersonFaces();
  window.scrollTo(0, 0);
  lastRendered = "";
  render();
}
document.querySelectorAll(".tab").forEach((t) => t.addEventListener("click", () => setView(t.dataset.view)));
$("#backToAlbums").onclick = () => setView("albums");

// ---------- gallery ----------
const currentEvent = () => events.find((e) => e.id === eventId);
function visible() {
  if (albumId) return videos.filter((v) => albumIds(v).includes(albumId));
  if (eventId) { const ids = new Set(currentEvent()?.video_ids || []); return videos.filter((v) => ids.has(v.id)); }
  if (personId) return videos.filter((v) => videoFaces(v).some(([, pid]) => pid === personId));
  if (filter === "fav") return videos.filter((v) => v.favorite);
  if (filter === "progress") return videos.filter((v) => IN_PROGRESS.has(v.status));
  if (filter === "phone") return videos.filter(onPhoneSafe);
  if (filter === "problems") return videos.filter((v) => PROBLEM.has(v.status));
  return videos;
}

function tile(v) {
  const hasThumb = v.status !== "new" || v.youtube_id;
  const prog = v.status === "uploading" ? `<div class="prog"><i style="width:${v.progress * 100}%"></i></div>` : "";
  return `<button class="tile${selected.has(v.id) ? " selected" : ""}" data-id="${v.id}" title="${esc(v.name)} — ${esc(statusText(v))}">
    ${hasThumb ? `<img loading="lazy" src="/media/thumbs/${v.id}.jpg" alt="">` : `<div class="ph">Preparing…</div>`}
    <span class="dot s-${v.status}"></span><span class="check"></span>${v.favorite ? '<span class="fav">♥</span>' : ""}<span class="dur">${fmtDur(v.duration)}</span>${prog}</button>`;
}

function renderGallery() {
  const list = visible();
  let html = "", month = "", day = "", open = false;
  for (const v of list) {
    const d = parseDate(v.recorded_at);
    const m = fmtMonth(d), dd = fmtDay(d);
    if (m !== month) { if (open) html += "</div>"; open = false; html += `<div class="month">${m}</div>`; month = m; day = ""; }
    if (dd !== day) { if (open) html += "</div>"; html += `<div class="day">${dd}${v.place ? " · " + esc(v.place) : ""}</div><div class="grid">`; open = true; day = dd; }
    html += tile(v);
  }
  if (open) html += "</div>";
  $("#gallery").innerHTML = html;

  const empty = $("#empty");
  empty.hidden = list.length > 0;
  empty.innerHTML = albumId ? `<p>This album is empty.</p><p class="muted">Go to <b>Videos</b>, tap <b>Select</b>, pick videos and choose <b>Add to album</b>.</p>`
    : videos.length ? `<p class="muted">Nothing here.</p>`
    : `<p>No videos yet.</p><p class="muted">Tap <b>＋</b> to add videos from this device${status.local ? ", or use <b>Import PC folder</b>" : ""}.</p>`;

  const n = (f) => videos.filter(f).length || "";
  $("#nProgress").textContent = n((v) => IN_PROGRESS.has(v.status));
  $("#nPhone").textContent = n(onPhoneSafe);
  renderFreeBar();
  $("#nProblems").textContent = n((v) => PROBLEM.has(v.status));
  $("#nFav").textContent = n((v) => v.favorite);
  document.querySelectorAll(".chip").forEach((c) => c.classList.toggle("active", c.dataset.filter === filter));

  if (albumId) {
    const a = albums.find((x) => x.id === albumId);
    if (!a) return setView("albums");
    $("#albumTitle").textContent = a.name;
  }
  if (eventId) {
    const e = currentEvent();
    if (!e) return setView("albums");  // event changed shape (new clips arrived)
    $("#albumTitle").textContent = e.name;
  }
  if (personId) {
    const p = currentPerson();
    if (!p) return setView("albums");  // merged or hidden
    $("#albumTitle").textContent = p.name || "Who is this?";
    $("#namePerson").textContent = p.name ? "Rename" : "Add name";
  }
}

const card = (attr, cover, name, sub) => `
  <button class="album-card" ${attr}>
    <div class="cover">${cover ? `<img loading="lazy" src="/media/thumbs/${cover}.jpg" alt="">` : "Empty"}</div>
    <div class="name">${esc(name)}</div><div class="count">${sub}</div>
  </button>`;

function renderAlbums() {
  renderPeople();
  $("#eventsBlock").hidden = !events.length;
  $("#eventGrid").innerHTML = events.map((e) => card(`data-event="${e.id}"`, e.cover, e.name, `${e.count} clips`)).join("");
  $("#albumGrid").innerHTML = albums.length
    ? albums.map((a) => card(`data-album="${a.id}"`, a.cover, a.name, `${a.count} video${a.count === 1 ? "" : "s"}`)).join("")
    : `<p class="muted">No albums yet.</p>`;
}

function render() {
  const key = [view, albumId, eventId, personId, JSON.stringify(people), filter, selecting, [...selected].join(), status.connected, status.user_paused,
    JSON.stringify(videos), JSON.stringify(albums), JSON.stringify(events)].join("|");
  if (key === lastRendered) return;
  lastRendered = key;
  if (view === "photos") renderGallery();
  else if (view === "albums") renderAlbums();
  else if (view === "map") showMap();
  if (openId) renderDetails();
}

$("#albumsView").addEventListener("click", (e) => {
  const c = e.target.closest(".album-card");
  if (!c) return;
  if (c.dataset.album) setView("photos", +c.dataset.album);
  else setView("photos", null, c.dataset.event);
});
$("#saveEvent").onclick = async () => {
  const ev = currentEvent();
  if (!ev) return;
  const name = prompt("Save this event as an album (also a private YouTube playlist). Name:", ev.name);
  if (!name || !name.trim()) return;
  const { id } = await api("/api/albums", { name });
  await api(`/api/albums/${id}/add`, { video_ids: ev.video_ids });
  await refresh();
  setView("photos", id);
};

// ---------- map ----------
let map = null, mapLayer = null, mapKey = "";
function showMap() {
  const located = videos.filter((v) => v.lat != null && v.lon != null);
  $("#mapEmpty").hidden = located.length > 0;
  if (!map) {
    map = L.map("map", { zoomControl: true, worldCopyJump: true }).setView([20.6, 78.9], 4);  // India
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
    const icon = L.divIcon({ className: "", iconSize: [54, 54], iconAnchor: [27, 27],
      html: `<div class="map-thumb" style="background-image:url('/media/thumbs/${v.id}.jpg')"></div>` });
    const when = parseDate(v.recorded_at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
    const m = L.marker([v.lat, v.lon], { icon });
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

document.querySelectorAll(".chip").forEach((c) => c.addEventListener("click", () => {
  filter = c.dataset.filter;
  history.replaceState(null, "", filter === "all" ? "/" : `/?filter=${filter}`);
  render();
}));

// ---------- selecting several videos ----------
function enterSelect() { selecting = true; document.body.classList.add("selecting"); updateSelBar(); }
function exitSelect() { selecting = false; selected.clear(); document.body.classList.remove("selecting"); updateSelBar(); lastRendered = ""; }
function updateSelBar() {
  $("#selBar").hidden = !selecting;
  $("#selCount").textContent = `${selected.size} selected`;
  $("#selAddAlbum").disabled = $("#selRemoveAlbum").disabled = !selected.size;
  $("#selRemoveAlbum").hidden = !albumId;
  $("#selDelete").disabled = !selected.size;
  $("#selectBtn").textContent = selecting ? "Done" : "Select";
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
};
$("#selRemoveAlbum").onclick = async () => {
  await api(`/api/albums/${albumId}/remove`, { video_ids: [...selected] });
  exitSelect(); refresh();
};

let pressTimer = null, longPressed = false;
$("#gallery").addEventListener("pointerdown", (e) => {
  const t = e.target.closest(".tile");
  if (!t || selecting) return;
  longPressed = false;
  pressTimer = setTimeout(() => { longPressed = true; enterSelect(); toggleSel(+t.dataset.id); }, 500);
});
["pointerup", "pointerleave", "pointercancel", "scroll"].forEach((ev) =>
  $("#gallery").addEventListener(ev, () => clearTimeout(pressTimer), { passive: true }));
$("#gallery").addEventListener("contextmenu", (e) => { if (e.target.closest(".tile")) e.preventDefault(); });

function toggleSel(id) {
  selected.has(id) ? selected.delete(id) : selected.add(id);
  document.querySelector(`.tile[data-id="${id}"]`)?.classList.toggle("selected", selected.has(id));
  updateSelBar();
}
$("#gallery").addEventListener("click", (e) => {
  const t = e.target.closest(".tile");
  if (!t) return;
  if (longPressed) { longPressed = false; return; }
  if (selecting) toggleSel(+t.dataset.id); else openViewer(+t.dataset.id);
});

// ---------- album picker ----------
let pickerVideos = [];
function openPicker(videoIds) {
  pickerVideos = videoIds;
  $("#pickerTitle").textContent = videoIds.length > 1 ? `Add ${videoIds.length} videos to album` : "Albums";
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
      <span>${esc(a.name)}<br><span class="muted">${a.count} video${a.count === 1 ? "" : "s"}</span></span>
      ${inSet.has(a.id) ? '<span class="tick">✓</span>' : ""}</button>`).join("")
    : `<div class="muted">No albums yet. Create one below.</div>`;
}
$("#pickerList").addEventListener("click", async (e) => {
  const b = e.target.closest(".picker-item");
  if (!b) return;
  const aid = +b.dataset.album;
  const isIn = b.classList.contains("in");
  await api(`/api/albums/${aid}/${isIn ? "remove" : "add"}`, { video_ids: pickerVideos });
  await refresh();
  if (pickerVideos.length > 1) { $("#picker").hidden = true; exitSelect(); render(); } else renderPicker();
});
$("#pickerNew").addEventListener("submit", async (e) => {
  e.preventDefault();
  const name = $("#pickerName").value.trim();
  if (!name) return;
  const { id } = await api("/api/albums", { name });
  if (pickerVideos.length) await api(`/api/albums/${id}/add`, { video_ids: pickerVideos });
  await refresh();
  $("#pickerName").value = "";
  if (pickerVideos.length > 1) { $("#picker").hidden = true; exitSelect(); render(); } else renderPicker();
});
$("#newAlbumBtn").onclick = async () => {
  const name = prompt("Album name");
  if (!name || !name.trim()) return;
  const { id } = await api("/api/albums", { name });
  await refresh();
  setView("photos", id);
};
$("#renameAlbum").onclick = async () => {
  const a = albums.find((x) => x.id === albumId);
  const name = prompt("Rename album", a?.name);
  if (!name || !name.trim()) return;
  await api(`/api/albums/${albumId}/rename`, { name });
  refresh();
};
$("#deleteAlbum").onclick = async () => {
  if (!confirm("Delete this album? Its YouTube playlist is deleted too. The videos stay.")) return;
  await api(`/api/albums/${albumId}/delete`, {});
  await refresh();
  setView("albums");
};

// ---------- free up space on the phone ----------
function renderFreeBar() {
  const list = videos.filter(onPhoneSafe);
  const bytes = list.reduce((s, v) => s + v.size, 0);
  const show = list.length > 0 && !albumId && (filter === "phone" || filter === "all");
  $("#freeBar").hidden = !show;
  if (!show) return;
  $("#freeTitle").textContent = `${list.length} video${list.length === 1 ? "" : "s"} (${fmtSize(bytes)}) safely on YouTube`;
  $("#freeSub").textContent = "You can remove them from your phone to free up space.";
}

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
    html += `<section><p><b>${d.synced.length} video${d.synced.length === 1 ? "" : "s"} · ${fmtSize(d.synced_bytes)}</b> synced by Syncthing.</p>
      <p class="muted">They'll be deleted from your phone the next time Syncthing syncs on Wi-Fi. They stay on YouTube in full
      quality and in Footage Vault as 720p previews. Each one is checked against YouTube again first.</p>
      ${freeList(d.synced)}<button class="btn primary" id="freeGo">Delete from phone · free ${fmtSize(d.synced_bytes)}</button></section>`;
  }
  if (d.manual.length) {
    const n = `<b>${d.manual.length} video${d.manual.length === 1 ? "" : "s"}</b>`;
    const how = d.has_phone_sync
      ? `${n} aren't synced yet. Once Syncthing copies them from your phone (next time it's on Wi-Fi), they appear above and can be deleted with one click.`
      : `${n} can't be deleted with one click yet. A website isn't allowed to delete files on your phone, so this goes through <b>Syncthing</b>.
         Set up the phone in Syncthing (SETUP.md, section 5) and these become one-click too.`;
    html += `<section><p>${how}</p>${freeList(d.manual)}
      <p class="muted">Or delete them in your phone's Gallery yourself, then tap:</p>
      <button class="btn" id="freeMark">I've deleted these myself</button></section>`;
  }
  $("#freeBody").innerHTML = html || '<p class="muted">Nothing to free up right now.</p>';
  const go = $("#freeGo");
  if (go) go.onclick = async () => {
    go.disabled = true; go.textContent = "Checking with YouTube…";
    try {
      const r = await api("/api/free-space", {});
      $("#freeBody").innerHTML = `<p class="note good">✓ ${r.freed} video${r.freed === 1 ? "" : "s"} (${fmtSize(r.bytes)}) will be removed from your phone at the next Wi-Fi sync.</p>` +
        (r.kept.length ? `<p class="note bad">Kept ${r.kept.length} because YouTube's copy didn't check out: ${r.kept.map(esc).join(", ")}</p>` : "");
    } catch (e) { $("#freeBody").innerHTML = `<p class="note bad">${esc(e.message)}</p>`; }
    refresh();
  };
  const mark = $("#freeMark");
  if (mark) mark.onclick = async () => {
    if (!confirm(`Only do this after deleting them from your phone. Mark ${d.manual.length} videos as deleted?`)) return;
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
  $("#delTitle").textContent = n === 1 ? `Delete “${videos.find((v) => v.id === ids[0])?.name}” forever?` : `Delete ${n} videos forever?`;
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
    $("#delBody").innerHTML = `<p class="note good">Deleted ${r.deleted} video${r.deleted === 1 ? "" : "s"} forever.</p>` +
      (r.skipped.length ? `<p class="note bad">Not deleted:<br>${r.skipped.map(esc).join("<br>")}</p>` : "") +
      `<button class="btn" onclick="document.getElementById('delSheet').hidden=true">OK</button>`;
    if (openId && delIds.includes(openId)) closeViewer();
    exitSelect();
  } catch (e) {
    $("#delBody").innerHTML = `<p class="note bad">${esc(e.message)}</p><button class="btn" onclick="document.getElementById('delSheet').hidden=true">OK</button>`;
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
  showInViewer(id);
}
function showInViewer(id) {
  openId = id;
  const v = videos.find((x) => x.id === id);
  if (!v) return closeViewer();
  const player = $("#player"), frame = $("#ytFrame");
  player.pause();
  const ytOnly = v.source === "youtube";
  const ready = !ytOnly && v.status !== "new";
  player.hidden = !ready;
  frame.hidden = !ytOnly;
  $("#noPreview").hidden = ready || ytOnly;
  if (ready) { player.poster = `/media/thumbs/${id}.jpg`; player.src = `/media/previews/${id}.mp4`; player.play().catch(() => {}); }
  else { player.removeAttribute("src"); player.load(); }
  // Videos uploaded straight to YouTube have no copy on the PC, so they play from YouTube.
  const ytSrc = ytOnly ? `https://www.youtube.com/embed/${encodeURIComponent(v.youtube_id)}?autoplay=1&playsinline=1&rel=0` : "about:blank";
  if (frame.src !== ytSrc) frame.src = ytSrc;
  renderViewerChrome();
  renderDetails();
}
function renderViewerChrome() {
  const v = videos.find((x) => x.id === openId);
  if (!v) return;
  const d = parseDate(v.recorded_at);
  $("#vDate").textContent = d.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
  $("#vSub").textContent = [d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" }), v.place].filter(Boolean).join(" · ");
  const yt = $("#vYt");
  yt.hidden = !v.youtube_id;
  if (v.youtube_id) yt.href = `https://www.youtube.com/watch?v=${encodeURIComponent(v.youtube_id)}`;
  $("#vFav").classList.toggle("on", !!v.favorite);
  $("#vFavIcon").textContent = v.favorite ? "♥" : "♡";
  const i = viewerList.indexOf(openId);
  $("#vPrev").disabled = i <= 0;
  $("#vNext").disabled = i < 0 || i >= viewerList.length - 1;
  $("#vDelete").disabled = v.status === "uploading";
}
function stepViewer(dir) {
  const i = viewerList.indexOf(openId) + dir;
  if (i < 0 || i >= viewerList.length) return;
  const stage = $("#stage");
  $("#viewer").classList.add("swipe-anim");
  stage.style.setProperty("--dx", "0");
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
$("#viewerClose").onclick = closeViewer;
$("#vPrev").onclick = () => stepViewer(-1);
$("#vNext").onclick = () => stepViewer(1);
$("#vInfo").onclick = () => { $("#viewer").classList.toggle("info-open"); renderDetails(); };
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
  if (!$("#viewer").hidden && !e.target.closest("input, textarea")) {
    if (e.key === "ArrowLeft") return stepViewer(-1);
    if (e.key === "ArrowRight") return stepViewer(1);
  }
  if (e.key !== "Escape") return;
  if (!$("#peoplePicker").hidden) $("#peoplePicker").hidden = true;
  else if (!$("#picker").hidden) $("#picker").hidden = true;
  else if (!$("#delSheet").hidden) $("#delSheet").hidden = true;
  else if ($("#viewer").classList.contains("info-open")) $("#viewer").classList.remove("info-open");
  else if (!$("#viewer").hidden) closeViewer();
  else if (!$("#settings").hidden) $("#settings").hidden = true;
  else if (selecting) { exitSelect(); render(); }
});

// Swipe left/right for next/previous, down to close (like Google Photos).
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
    else if (dy < -90 && Math.abs(dy) > Math.abs(dx) * 1.5) { $("#viewer").classList.add("info-open"); renderDetails(); }
  }, { passive: true });
})();

const personName = (pid) => people.find((p) => p.id === pid)?.name;
const videoFaces = (v) => (v.faces ? String(v.faces).split(",").map((s) => s.split(":").map(Number)) : []);

function renderDetails() {
  const v = videos.find((x) => x.id === openId);
  if (!v) return closeViewer();
  renderViewerChrome();
  if (!$("#viewer").classList.contains("info-open")) return;
  const d = parseDate(v.recorded_at);
  let note = "";
  if (onPhoneSafe(v))
    note = `<div class="note good">✓ Safely on YouTube. You can delete <b>${esc(v.name)}</b> from your phone${v.synced ? " (or use <b>Free up space</b>)" : ""}.</div>`;
  else if (v.source === "phone" && v.phone_cleared_at)
    note = `<div class="note good">✓ On YouTube and removed from your phone.</div>`;
  else if (v.source === "phone" && !PROBLEM.has(v.status))
    note = `<div class="note">Keep this video on your phone until it says it's safe to delete.</div>`;
  else if (v.source === "youtube")
    note = `<div class="note">Uploaded to YouTube directly, so it plays from YouTube. ${v.privacy === "private" ? "YouTube may only play private videos in the YouTube app/site — use ▶ at the top." : ""}</div>`;
  if (v.error) note += `<div class="note bad">${esc(v.error)}</div>`;

  const chips = albumIds(v).map((id) => albums.find((a) => a.id === id)).filter(Boolean)
    .map((a) => `<button class="album-chip" data-goto="${a.id}">${esc(a.name)}</button>`).join("");
  const seen = new Set();
  // Only people listed in People (named or in 2+ clips); ✕ = "not them" for this one face.
  const faceChips = videoFaces(v).filter(([, pid]) => people.some((p) => p.id === pid) && !seen.has(pid) && seen.add(pid))
    .map(([fid, pid]) => `<span class="face-wrap"><button class="face-chip" data-person="${pid}"><img src="/media/faces/${fid}.jpg" alt="">${esc(personName(pid) || "Add name")}</button>
      <button class="not-them" data-notme="${fid}" title="Not ${esc(personName(pid) || "this person")}" aria-label="Not this person">✕</button></span>`).join("");
  const retry = PROBLEM.has(v.status) ? `<button class="btn primary" id="retryBtn">${v.status === "hold" ? "Re-check" : "Retry"}</button>` : "";
  const yt = v.youtube_id ? `<a class="btn" target="_blank" rel="noopener" href="https://studio.youtube.com/video/${esc(v.youtube_id)}/edit">YouTube Studio</a>` : "";

  $("#details").innerHTML = `<h3>${esc(v.name)}</h3>
    <div class="muted">${d.toLocaleString(undefined, { dateStyle: "full", timeStyle: "short" })}${v.place ? " · " + esc(v.place) : ""}</div>
    ${note}
    ${faceChips ? `<h4>People</h4><div class="faces-row">${faceChips}</div>` : ""}
    <h4>Albums</h4><div class="album-chips">${chips}<button class="album-chip" id="addToAlbum">＋ Add to album</button></div>
    <h4>Details</h4>
    <dl><dt>Status</dt><dd>${esc(statusText(v))}</dd>
      <dt>Length</dt><dd>${fmtDur(v.duration)}</dd>
      ${v.width ? `<dt>Resolution</dt><dd>${v.width}×${v.height}</dd>` : ""}
      ${v.size ? `<dt>Size</dt><dd>${fmtSize(v.size)}</dd>` : ""}
      <dt>From</dt><dd>${v.source === "phone" ? "Phone" : v.source === "youtube" ? "YouTube" : "PC"}</dd>
      ${v.privacy ? `<dt>On YouTube</dt><dd>${esc(v.privacy)}</dd>` : ""}</dl>
    <div class="row">${yt}${retry}</div>
    ${v.source !== "youtube" ? '<p class="muted" style="margin-top:12px;font-size:13px">Plays a 720p copy kept on your PC. ▶ at the top opens full quality on YouTube.</p>' : ""}`;
  $("#addToAlbum").onclick = () => openPicker([v.id]);
  $("#details").querySelectorAll("[data-goto]").forEach((b) => b.onclick = () => { closeViewer(); setView("photos", +b.dataset.goto); });
  $("#details").querySelectorAll("[data-person]").forEach((b) => b.onclick = () => { closeViewer(); setView("photos", null, null, +b.dataset.person); });
  $("#details").querySelectorAll("[data-notme]").forEach((b) => b.onclick = async () => {
    b.disabled = true;
    await api(`/api/faces/${b.dataset.notme}/remove`, {});
    refresh();
  });
  const rb = $("#retryBtn");
  if (rb) rb.onclick = async () => { await api(`/api/videos/${v.id}/retry`, {}); refresh(); };
}

// ---------- people ----------
let people = [];
let peopleInfo = { others: 0, scan: {} };
let personId = null;
const currentPerson = () => people.find((p) => p.id === personId);
const personCard = (p, attr = `data-pid="${p.id}"`) => `<button class="person" ${attr}>
  ${p.cover_face ? `<img loading="lazy" src="/media/faces/${p.cover_face}.jpg" alt="">` : '<span class="ph-face"></span>'}
  <div class="pname${p.name ? "" : " unnamed"}">${esc(p.name || "Add name")}</div><div class="pcount">${p.videos} clip${p.videos === 1 ? "" : "s"}</div></button>`;
function renderPeople() {
  const s = peopleInfo.scan || {};
  const missing = s.models === "missing";
  $("#peopleBlock").hidden = !people.length && !s.running && !missing;
  $("#peopleNote").textContent = missing ? "Face models missing — they download automatically when the PC is online"
    : s.running ? `Finding faces… ${s.done}/${s.total} clips` : "Found on this PC — nothing is uploaded";
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
    <button data-notme="${f.id}" title="Not this person">✕</button></div>`).join("");
}
$("#personFaces").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-notme]");
  if (!b) return;
  await api(`/api/faces/${b.dataset.notme}/remove`, {});
  await refresh(); renderPersonFaces();
});
$("#namePerson").onclick = async () => {
  const p = currentPerson();
  const name = prompt("Who is this?", p?.name || "");
  if (name === null) return;
  await api(`/api/people/${personId}/name`, { name });
  refresh();
};
$("#hidePerson").onclick = async () => {
  if (!confirm("Hide this person from People? Their clips stay.")) return;
  await api(`/api/people/${personId}/hide`, {});
  await refresh(); setView("albums");
};
$("#mergePerson").onclick = () => {
  $("#peoplePickerList").innerHTML = people.filter((p) => p.id !== personId).map((p) => personCard(p, `data-into="${p.id}"`)).join("")
    || '<p class="muted">No one else yet.</p>';
  $("#peoplePicker").hidden = false;
};
$("#peoplePickerList").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-into]");
  if (!b) return;
  const into = +b.dataset.into;
  await api(`/api/people/${personId}/merge`, { into });
  $("#peoplePicker").hidden = true;
  await refresh(); setView("photos", null, null, into);
});

// ---------- sending files from this device (phone) ----------
const sendQueue = [];
let sending = false;
let sendStats = { done: 0, dup: 0, failed: 0 };

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
  $("#sendQueue").innerHTML = `Sent ${s.done} video${s.done === 1 ? "" : "s"} to the PC` +
    (s.dup ? ` · ${s.dup} already in vault` : "") + (s.failed ? ` · <b>${s.failed} failed</b>` : "") +
    ` <button class="btn" onclick="this.parentElement.hidden=true">OK</button>`;
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
      box.innerHTML = `Sending to PC: <b>${esc(file.name)}</b> (${remaining} left) — keep this page open
        <div class="bar"><i style="width:${pct}%"></i></div>`;
    };
    xhr.onload = () => xhr.status < 300 ? resolve(JSON.parse(xhr.responseText))
      : reject(new Error(xhr.responseText));
    xhr.onerror = () => reject(new Error("Network error"));
    xhr.send(file);
  });
}
window.addEventListener("beforeunload", (e) => { if (sending) { e.preventDefault(); e.returnValue = ""; } });

// ---------- PC-only actions ----------
$("#importBtn").onclick = async () => {
  const folder = prompt("Folder on this PC to import (subfolders included).\nOriginals go to the Recycle Bin after they're safely on YouTube.");
  if (!folder) return;
  try { await api("/api/import", { folder }); } catch (e) { alert(e.message); }
  refresh();
};

$("#settingsBtn").onclick = () => { $("#settings").hidden = false; $("#deleteDays").value = status.delete_after_days; renderSettings(); renderPush(); };
document.querySelectorAll("[data-close]").forEach((b) => b.onclick = () => { $("#" + b.dataset.close).hidden = true; });
document.querySelectorAll(".sheet").forEach((s) => s.addEventListener("click", (e) => { if (e.target === s) s.hidden = true; }));
$("#connectBtn").onclick = connect;
$("#disconnectBtn").onclick = async () => {
  if (!confirm("Disconnect YouTube? Uploads pause until you connect again.")) return;
  await api("/api/youtube/logout", {}); refresh();
};
$("#saveDays").onclick = async () => { await api("/api/settings", { delete_after_days: +$("#deleteDays").value }); refresh(); };

async function addWatch(source) {
  const msg = source === "phone"
    ? "Folder on this PC that Syncthing copies your phone's videos into:"
    : "Folder on this PC to keep watching for new videos:";
  const folder = prompt(msg);
  if (!folder) return;
  try { await api("/api/watch", { folder, source }); } catch (e) { return alert(e.message); }
  await refresh(); renderSettings();
}
$("#addWatchPhone").onclick = () => addWatch("phone");
$("#addWatchPc").onclick = () => addWatch("pc");
$("#watchList").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-unwatch]");
  if (!b || !confirm("Stop watching this folder? Videos already added stay in the vault.")) return;
  await api("/api/watch/remove", { folder: b.dataset.unwatch });
  await refresh(); renderSettings();
});

$("#ytSyncBtn").onclick = async () => {
  await api("/api/youtube/import", {});
  $("#ytSyncState").textContent = "Syncing…";
  setTimeout(refresh, 8000);
};

async function connect() {
  // Open the tab first (synchronously) so phones don't block it as a popup.
  const tab = window.open("about:blank", "_blank");
  let url;
  try { ({ url } = await api("/api/youtube/login", {})); } catch (e) { tab?.close(); return alert(e.message); }
  if (tab) tab.location = url; else location.href = url;
  if (!status.local) {
    // Google can only return to the PC's own address, so on the phone the user pastes it back.
    $("#settings").hidden = false;
    renderSettings();
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
  catch (err) { return alert(err.message); }
  $("#pasteBox").hidden = true;
  channelName = null;
  await refresh();
  renderSettings();
  alert("✓ YouTube connected");
});

let channelName = null;
async function renderSettings() {
  $("#lanUrl").textContent = status.lan_url || "";
  $("#pin").textContent = status.pin || "";
  $("#connectBtn").hidden = status.connected;
  $("#connectBtn").textContent = status.auth === "none" ? "Connect YouTube" : "Reconnect YouTube";
  $("#disconnectBtn").hidden = !status.connected;
  if (status.connected && channelName === null) {
    channelName = (await api("/api/channel")).title || "";
  }
  $("#ytState").textContent = !status.has_client_secret ? "Not set up yet: client_secret.json is missing (see SETUP.md)."
    : status.connected ? `Connected${channelName ? " to " + channelName : ""}. Uploads are private.`
    : status.auth === "scope" ? "Reconnect needed: albums need one extra YouTube permission."
    : status.auth === "expired" ? "Google sign-in ran out. Reconnect to continue uploading."
    : "Not connected.";

  const yi = status.yt_import;
  if ($("#ytSyncState").textContent !== "Syncing…" || yi)
    $("#ytSyncState").textContent = yi ? `Last sync ${yi.at.slice(11, 16)}: ${yi.added} new video${yi.added === 1 ? "" : "s"}, ${yi.albums} new album${yi.albums === 1 ? "" : "s"}` : "";
  const ts = status.tailscale;
  $("#tsBlock").innerHTML = !ts ? `<p class="muted">Tailscale isn't running on this PC.</p>`
    : ts.https ? `<p class="muted">From anywhere (Tailscale). On your phone open this in Chrome, then <b>⋮ → Install app</b>:</p><div class="code">${esc(ts.url)}</div>`
    : `<p class="muted">Tailscale is on, but HTTPS certificates are off. Turn them on (see SETUP.md) to install the app and get notifications on your phone.</p>`;

  const wf = status.watch_folders || [];
  $("#watchList").innerHTML = wf.length ? wf.map((f) => `<div class="watch-item"><span class="p">${esc(f.path)}</span>
    <span class="tag">${f.source === "phone" ? "phone" : "PC"}</span>
    <button class="btn" data-unwatch="${esc(f.path)}">Remove</button></div>`).join("") : `<p class="muted">None yet.</p>`;
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
  if (!pushSupported) {
    st.innerHTML = "Notifications need the secure Tailscale address (https://…). Open Footage Vault from that address on your phone.";
    return;
  }
  if (Notification.permission === "denied") {
    st.textContent = "Notifications are blocked for this site. Allow them in the browser's site settings.";
    return;
  }
  const sub = await currentSub().catch(() => null);
  if (sub) {
    st.innerHTML = '<span class="ok-text">On.</span> You\'ll get a notification when videos are safely on YouTube or something needs attention.';
    $("#pushOff").hidden = $("#pushTest").hidden = false;
  } else {
    st.textContent = "Get notified when videos are safely on YouTube (so you can delete them from your phone) or if something needs attention.";
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
  } catch (e) { alert("Couldn't turn on notifications: " + e.message); }
  renderPush();
};
$("#pushOff").onclick = async () => {
  const sub = await currentSub();
  if (sub) { await api("/api/push/unsubscribe", { endpoint: sub.endpoint }); await sub.unsubscribe(); }
  renderPush();
};
$("#pushTest").onclick = async () => {
  const { sent } = await api("/api/push/test", {});
  if (!sent) alert("No device accepted the test. Try turning notifications off and on again.");
};

let installPrompt = null;
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installPrompt = e; renderBanner(); });
window.addEventListener("appinstalled", () => { installPrompt = null; renderBanner(); });

// ---------- banner ----------
let importDismissed = "";
let installDismissed = false;
function renderBanner() {
  const b = $("#banner");
  let html = "", warn = false;
  const imp = status.import;
  if (status.local && !status.has_client_secret) {
    html = "YouTube isn't set up yet. Follow <b>SETUP.md</b> in the footage-vault folder."; warn = true;
  } else if (!status.connected) {
    html = status.auth === "scope" ? "Reconnect YouTube once so albums can create playlists. Uploads are paused until then."
      : status.auth === "expired" ? "Google sign-in ran out (this happens every 7 days in Testing mode). Uploads are paused."
      : status.auth === "offline" ? "No internet connection. Uploads continue when it's back."
      : "YouTube is not connected. Videos are kept and will upload once it's connected.";
    if (status.has_client_secret && status.auth !== "offline")
      html += ` <button class="btn primary" id="bannerConnect">${status.auth === "none" ? "Connect" : "Reconnect"} YouTube</button>`;
    warn = true;
  } else if (status.user_paused) {
    html = "Uploads are paused (tray icon → Pause uploads).";
  } else if (status.paused_until) {
    html = `${esc(status.pause_reason)}. Uploads resume ${esc(status.paused_until)}.`; warn = true;
  }
  if (imp && imp.running) html += (html ? "<br>" : "") + `Importing ${esc(imp.folder)}… found ${imp.found}, added ${imp.added}`;
  else if (imp && status.local && importDismissed !== imp.folder + imp.found) {
    html += (html ? "<br>" : "") + `Import finished: ${imp.added} added, ${imp.skipped} already in vault` +
      (imp.errors ? `, ${imp.errors} unreadable` : "") + ' <button class="btn" id="importOk">OK</button>';
  }
  if (installPrompt && !installDismissed) {
    html += (html ? "<br>" : "") + 'Install Footage Vault as an app? <button class="btn primary" id="installBtn">Install</button> <button class="btn" id="installNo">Not now</button>';
  }
  b.innerHTML = html;
  b.hidden = !html;
  b.classList.toggle("warn", warn);
  const bc = $("#bannerConnect");
  if (bc) bc.onclick = connect;
  const ok = $("#importOk");
  if (ok) ok.onclick = () => { importDismissed = imp.folder + imp.found; renderBanner(); };
  const ib = $("#installBtn");
  if (ib) ib.onclick = async () => { installPrompt.prompt(); await installPrompt.userChoice; installPrompt = null; renderBanner(); };
  const inb = $("#installNo");
  if (inb) inb.onclick = () => { installDismissed = true; renderBanner(); };
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
    renderBanner();
    if (!$("#settings").hidden) renderSettings();
  } catch (e) { /* PC asleep or offline; try again next tick */ }
}
setView("photos");
refresh();
setInterval(refresh, 3000);
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });

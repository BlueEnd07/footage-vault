const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

let videos = [];
let status = {};
let filter = "all";
let lastRendered = "";
let openId = null;

const IN_PROGRESS = new Set(["new", "queued", "uploading", "uploaded"]);
const ON_YT = new Set(["on_youtube", "done"]);
const PROBLEM = new Set(["failed", "hold"]);

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

function statusText(v) {
  switch (v.status) {
    case "new": return "Preparing preview";
    case "queued": return status.connected ? "Waiting to upload" : "Waiting — YouTube not connected";
    case "uploading": return `Uploading to YouTube · ${Math.round(v.progress * 100)}%`;
    case "uploaded": return "Uploaded · YouTube is processing";
    case "on_youtube": {
      const when = v.delete_after ? parseDate(v.delete_after).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "";
      return v.source === "pc" ? `On YouTube · original goes to Recycle Bin ${when}` : `On YouTube · PC copy cleared ${when}`;
    }
    case "done": return "On YouTube · original deleted";
    case "hold": return "On YouTube · original kept (check failed)";
    case "failed": return "Failed";
  }
  return v.status;
}

// ---------- gallery ----------
function visible() {
  if (filter === "progress") return videos.filter((v) => IN_PROGRESS.has(v.status));
  if (filter === "phone") return videos.filter((v) => v.source === "phone" && ON_YT.has(v.status));
  if (filter === "problems") return videos.filter((v) => PROBLEM.has(v.status));
  return videos;
}

function tile(v) {
  const hasThumb = v.status !== "new" || v.youtube_id;
  const prog = v.status === "uploading" ? `<div class="prog"><i style="width:${v.progress * 100}%"></i></div>` : "";
  return `<button class="tile" data-id="${v.id}" title="${esc(v.name)} — ${esc(statusText(v))}">
    ${hasThumb ? `<img loading="lazy" src="/media/thumbs/${v.id}.jpg" alt="">` : `<div class="ph">Preparing…</div>`}
    <span class="dot s-${v.status}"></span><span class="dur">${fmtDur(v.duration)}</span>${prog}</button>`;
}

function render() {
  const list = visible();
  const key = filter + JSON.stringify(list) + status.connected;
  if (key === lastRendered) return;
  lastRendered = key;

  let html = "", month = "", day = "", open = false;
  for (const v of list) {
    const d = parseDate(v.recorded_at);
    const m = fmtMonth(d), dd = fmtDay(d);
    if (m !== month) { if (open) html += "</div>"; open = false; html += `<div class="month">${m}</div>`; month = m; day = ""; }
    if (dd !== day) { if (open) html += "</div>"; html += `<div class="day">${dd}</div><div class="grid">`; open = true; day = dd; }
    html += tile(v);
  }
  if (open) html += "</div>";
  $("#gallery").innerHTML = html;
  $("#empty").hidden = videos.length > 0;

  const n = (f) => videos.filter(f).length || "";
  $("#nProgress").textContent = n((v) => IN_PROGRESS.has(v.status));
  $("#nPhone").textContent = n((v) => v.source === "phone" && ON_YT.has(v.status));
  $("#nProblems").textContent = n((v) => PROBLEM.has(v.status));

  if (openId) renderDetails();
}

$("#gallery").addEventListener("click", (e) => {
  const t = e.target.closest(".tile");
  if (t) openViewer(+t.dataset.id);
});
document.querySelectorAll(".chip").forEach((c) => c.addEventListener("click", () => {
  document.querySelectorAll(".chip").forEach((x) => x.classList.toggle("active", x === c));
  filter = c.dataset.filter;
  render();
}));

// ---------- viewer ----------
function openViewer(id) {
  openId = id;
  const v = videos.find((x) => x.id === id);
  const player = $("#player");
  const ready = v.status !== "new";
  player.hidden = !ready;
  $("#noPreview").hidden = ready;
  if (ready) { player.poster = `/media/thumbs/${id}.jpg`; player.src = `/media/previews/${id}.mp4`; player.play().catch(() => {}); }
  $("#viewer").hidden = false;
  renderDetails();
}
function closeViewer() {
  openId = null;
  const p = $("#player"); p.pause(); p.removeAttribute("src"); p.load();
  $("#viewer").hidden = true;
}
$("#viewerClose").onclick = closeViewer;
document.addEventListener("keydown", (e) => { if (e.key === "Escape") { closeViewer(); $("#settings").hidden = true; } });

function renderDetails() {
  const v = videos.find((x) => x.id === openId);
  if (!v) return closeViewer();
  const d = parseDate(v.recorded_at);
  let note = "";
  if (v.source === "phone" && ON_YT.has(v.status))
    note = `<div class="note good">✓ Safely on YouTube. You can delete <b>${esc(v.name)}</b> from your phone.</div>`;
  else if (v.source === "phone" && !PROBLEM.has(v.status))
    note = `<div class="note">Keep this video on your phone until it says it's safe to delete.</div>`;
  if (v.error) note += `<div class="note bad">${esc(v.error)}</div>`;

  const yt = v.youtube_id ? `<a class="btn" target="_blank" rel="noopener" href="https://www.youtube.com/watch?v=${esc(v.youtube_id)}">Watch on YouTube (full quality)</a>
    <a class="btn" target="_blank" rel="noopener" href="https://studio.youtube.com/video/${esc(v.youtube_id)}/edit">YouTube Studio</a>` : "";
  const retry = PROBLEM.has(v.status) ? `<button class="btn primary" id="retryBtn">${v.status === "hold" ? "Re-check" : "Retry"}</button>` : "";

  $("#details").innerHTML = `<h3>${esc(v.name)}</h3><div class="muted">${d.toLocaleString(undefined, { dateStyle: "full", timeStyle: "short" })}</div>
    <dl><dt>Status</dt><dd>${esc(statusText(v))}</dd>
      <dt>Length</dt><dd>${fmtDur(v.duration)}</dd>
      <dt>Resolution</dt><dd>${v.width}×${v.height}</dd>
      <dt>Size</dt><dd>${fmtSize(v.size)}</dd>
      <dt>From</dt><dd>${v.source === "phone" ? "Phone" : "PC"}</dd></dl>
    ${note}<div class="row">${yt}${retry}</div>
    <p class="muted" style="margin-top:16px;font-size:13px">The player here uses a 720p copy kept on your PC.</p>`;
  const rb = $("#retryBtn");
  if (rb) rb.onclick = async () => { await fetch(`/api/videos/${v.id}/retry`, { method: "POST" }); refresh(); };
}

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
  const r = await fetch("/api/import", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ folder }) });
  if (!r.ok) alert((await r.json()).detail);
  refresh();
};

$("#settingsBtn").onclick = () => { $("#settings").hidden = false; $("#deleteDays").value = status.delete_after_days; renderSettings(); };
$("#settingsClose").onclick = () => { $("#settings").hidden = true; };
$("#settings").addEventListener("click", (e) => { if (e.target.id === "settings") e.target.hidden = true; });
$("#connectBtn").onclick = connect;
$("#disconnectBtn").onclick = async () => {
  if (!confirm("Disconnect YouTube? Uploads pause until you connect again.")) return;
  await fetch("/api/youtube/logout", { method: "POST" }); refresh();
};
$("#saveDays").onclick = async () => {
  await fetch("/api/settings", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ delete_after_days: +$("#deleteDays").value }) });
  refresh();
};

async function connect() {
  const r = await fetch("/api/youtube/login", { method: "POST" });
  if (!r.ok) return alert((await r.json()).detail);
  refresh();
}

let channelName = null;
async function renderSettings() {
  $("#lanUrl").textContent = status.lan_url || "";
  $("#pin").textContent = status.pin || "";
  $("#connectBtn").hidden = status.connected;
  $("#disconnectBtn").hidden = !status.connected;
  if (status.connected && channelName === null) {
    channelName = (await (await fetch("/api/channel")).json()).title || "";
  }
  $("#ytState").textContent = !status.has_client_secret ? "Not set up yet: client_secret.json is missing (see SETUP.md)."
    : status.connected ? `Connected${channelName ? " to " + channelName : ""}. Uploads are private.`
    : status.login_running ? "Finish signing in in the browser window that opened on the PC…"
    : "Not connected.";
}

let importDismissed = "";
function renderBanner() {
  const b = $("#banner");
  let html = "", warn = false;
  const imp = status.import;
  if (status.local && !status.has_client_secret) {
    html = "YouTube isn't set up yet. Follow <b>SETUP.md</b> in the footage-vault folder, then restart the app."; warn = true;
  } else if (!status.connected) {
    html = status.login_running ? "Finish Google sign-in in the browser window on the PC…"
      : "YouTube is not connected. Videos are kept and will upload once it's connected." +
        (status.local ? ' <button class="btn primary" id="bannerConnect">Connect YouTube</button>' : "");
    warn = true;
  } else if (status.paused_until) {
    html = `${esc(status.pause_reason)}. Uploads resume ${esc(status.paused_until)}.`; warn = true;
  }
  if (imp && imp.running) html += (html ? "<br>" : "") + `Importing ${esc(imp.folder)}… found ${imp.found}, added ${imp.added}`;
  else if (imp && status.local && importDismissed !== imp.folder + imp.found) {
    html += (html ? "<br>" : "") + `Import finished: ${imp.added} added, ${imp.skipped} already in vault` +
      (imp.errors ? `, ${imp.errors} unreadable` : "") + ' <button class="btn" id="importOk">OK</button>';
  }
  b.innerHTML = html;
  b.hidden = !html;
  b.classList.toggle("warn", warn);
  const bc = $("#bannerConnect");
  if (bc) bc.onclick = connect;
  const ok = $("#importOk");
  if (ok) ok.onclick = () => { importDismissed = imp.folder + imp.found; renderBanner(); };
}

// ---------- polling ----------
async function refresh() {
  try {
    const [v, s] = await Promise.all([fetch("/api/videos"), fetch("/api/status")]);
    if (v.status === 401) return location.reload();
    videos = await v.json();
    status = await s.json();
    document.body.classList.toggle("remote", !status.local);
    render();
    renderBanner();
    if (!$("#settings").hidden) renderSettings();
  } catch (e) { /* PC asleep or offline; try again next tick */ }
}
refresh();
setInterval(refresh, 3000);

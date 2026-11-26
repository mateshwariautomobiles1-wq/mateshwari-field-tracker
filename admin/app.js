import { firebaseConfig, APK_URL, OFFLINE_AFTER_MIN } from "./config.js";
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore, collection, doc, onSnapshot, getDoc, getDocs, writeBatch, updateDoc,
  query, where, orderBy, limit, Timestamp, serverTimestamp,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

const fbApp = initializeApp(firebaseConfig);
const auth = getAuth(fbApp);
const db = getFirestore(fbApp);

const $ = (id) => document.getElementById(id);

// ---------- state ----------
const employees = new Map(); // mobile -> employee doc
const live = new Map();      // mobile -> live doc
let unsubs = [];
let selected = null;         // mobile of selected employee
let dayPoints = [];
let map, liveLayer, historyLayer;
let needFit = true;
const addrCache = new Map();

const STATUS = {
  online:   { label: "Online",          color: "#16a34a", rank: 0 },
  gpsoff:   { label: "GPS OFF",         color: "#ea580c", rank: 1 },
  offline:  { label: "Offline",         color: "#9ca3af", rank: 2 },
  never:    { label: "App login nahi",  color: "#cbd5e1", rank: 3 },
  inactive: { label: "Band",            color: "#475569", rank: 4 },
};

// ---------- helpers ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const toDate = (ts) => (ts && typeof ts.toDate === "function" ? ts.toDate() : null);
const pad = (n) => String(n).padStart(2, "0");
const todayStr = () => { const d = new Date(); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
const fmtTime = (d) => d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" });
const fmtDur = (ms) => { const m = Math.round(ms / 60000); return m < 60 ? `${m} min` : `${Math.floor(m / 60)} ghanta${m % 60 ? ` ${m % 60} min` : ""}`; };
const initials = (name) => (name || "?").trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
const randomPin = () => String(Math.floor(1000 + Math.random() * 9000));

function timeAgo(d) {
  if (!d) return "—";
  const s = (Date.now() - d.getTime()) / 1000;
  if (s < 60) return "abhi";
  if (s < 3600) return `${Math.floor(s / 60)} min pehle`;
  if (s < 86400) return `${Math.floor(s / 3600)} ghante pehle`;
  return d.toLocaleString("en-IN", { day: "2-digit", month: "short", hour: "2-digit", minute: "2-digit" });
}

function distM(a, b) {
  const R = 6371000, toR = Math.PI / 180;
  const dLat = (b.lat - a.lat) * toR, dLng = (b.lng - a.lng) * toR;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * toR) * Math.cos(b.lat * toR) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}

function statusOf(emp) {
  if (!emp || emp.active !== true) return "inactive";
  const l = live.get(emp.id);
  if (!l) return "never";
  const seen = toDate(l.seenAt) || toDate(l.updatedAt);
  if (!seen || Date.now() - seen.getTime() > OFFLINE_AFTER_MIN * 60000) return "offline";
  if (l.gpsOn === false) return "gpsoff";
  return "online";
}

// ---------- auth ----------
onAuthStateChanged(auth, (user) => {
  if (user) {
    $("loginScreen").classList.add("hidden");
    $("mainScreen").classList.remove("hidden");
    initMap();
    startListeners();
  } else {
    stopListeners();
    $("mainScreen").classList.add("hidden");
    $("loginScreen").classList.remove("hidden");
  }
});

$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("loginErr").textContent = "";
  try {
    await signInWithEmailAndPassword(auth, $("loginEmail").value.trim(), $("loginPass").value);
  } catch (err) {
    $("loginErr").textContent =
      err.code === "auth/invalid-credential" || err.code === "auth/wrong-password"
        ? "Email ya password galat hai"
        : err.message;
  }
});
$("logoutBtn").onclick = () => signOut(auth);

function onDbError(err) {
  console.error(err);
  if (err.code === "permission-denied") {
    signOut(auth);
    $("loginErr").textContent =
      "Is email ko admin access nahi hai. firestore.rules me apna admin email daal kar Publish karein.";
  }
}

function startListeners() {
  stopListeners();
  unsubs.push(onSnapshot(collection(db, "employees"), (snap) => {
    employees.clear();
    snap.forEach((d) => employees.set(d.id, { id: d.id, ...d.data() }));
    renderAll();
  }, onDbError));
  unsubs.push(onSnapshot(collection(db, "live"), (snap) => {
    live.clear();
    snap.forEach((d) => live.set(d.id, d.data()));
    renderAll();
  }, onDbError));
}
function stopListeners() { unsubs.forEach((u) => u()); unsubs = []; employees.clear(); live.clear(); }

// ---------- map ----------
function initMap() {
  if (map) { setTimeout(() => map.invalidateSize(), 50); return; }
  map = L.map("map", { zoomControl: true }).setView([22.6, 79.0], 5);
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: "&copy; OpenStreetMap",
  }).addTo(map);
  historyLayer = L.layerGroup().addTo(map);
  liveLayer = L.layerGroup().addTo(map);
  map.on("popupopen", (e) => {
    const btn = e.popup.getElement()?.querySelector("[data-open]");
    if (btn) btn.onclick = () => selectEmp(btn.dataset.open);
  });
  setTimeout(() => map.invalidateSize(), 50);
}

function pinIcon(emp, st) {
  return L.divIcon({
    className: "",
    html: `<div class="pin" style="background:${STATUS[st].color}"><span>${esc(initials(emp.name))}</span></div>`,
    iconSize: [34, 34], iconAnchor: [17, 34], popupAnchor: [0, -32],
  });
}
const dotIcon = (color) => L.divIcon({ className: "", html: `<div class="dot" style="background:${color}"></div>`, iconSize: [14, 14], iconAnchor: [7, 7] });

function renderMarkers() {
  if (!map) return;
  liveLayer.clearLayers();
  const ids = selected ? [selected] : [...employees.keys()];
  const pts = [];
  for (const id of ids) {
    const emp = employees.get(id), l = live.get(id);
    if (!emp || !l || typeof l.lat !== "number") continue;
    const st = statusOf(emp);
    const t = toDate(l.t);
    const seen = toDate(l.seenAt);
    const m = L.marker([l.lat, l.lng], { icon: pinIcon(emp, st), zIndexOffset: 1000 }).bindPopup(`
      <b>${esc(emp.name)}</b> <span class="badge s-${st}">${STATUS[st].label}</span><br>
      <span class="small">📍 Location: ${timeAgo(t)}${t ? ` (${fmtTime(t)})` : ""}</span><br>
      <span class="small">📶 Last contact: ${timeAgo(seen)}</span><br>
      <span class="small">🔋 ${l.battery ?? "?"}%${l.charging ? " 🔌" : ""} · ±${Math.round(l.acc || 0)}m</span><br>
      <a href="https://www.google.com/maps?q=${l.lat},${l.lng}" target="_blank" rel="noopener">Google Maps me kholein</a>
      ${selected ? "" : `<br><button class="btn small" style="margin-top:6px" data-open="${esc(id)}">Aaj ka route dekhein</button>`}
    `);
    liveLayer.addLayer(m);
    pts.push([l.lat, l.lng]);
  }
  if (needFit && !selected && pts.length) {
    map.fitBounds(pts, { padding: [40, 40], maxZoom: 15 });
    needFit = false;
  }
}

// ---------- list ----------
function renderAll() {
  renderList();
  renderMarkers();
  if (selected) renderDetail();
}

function renderList() {
  const q = $("search").value.trim().toLowerCase();
  const arr = [...employees.values()]
    .filter((e) => !q || (e.name || "").toLowerCase().includes(q) || e.id.includes(q))
    .map((e) => ({ e, st: statusOf(e) }))
    .sort((a, b) => STATUS[a.st].rank - STATUS[b.st].rank || (a.e.name || "").localeCompare(b.e.name || ""));

  const all = [...employees.values()].map(statusOf);
  const count = (k) => all.filter((s) => s === k).length;
  $("summary").textContent =
    `${employees.size} employees · ${count("online")} online · ${count("gpsoff")} GPS off · ${count("offline")} offline`;

  const ul = $("empList");
  if (!employees.size) {
    ul.innerHTML = `<li class="empty">Abhi koi employee nahi hai.<br>Upar <b>+ Employee</b> dabaa kar banayein.</li>`;
    return;
  }
  ul.innerHTML = arr.map(({ e, st }) => {
    const l = live.get(e.id);
    const sub = l ? `${timeAgo(toDate(l.seenAt))} · 🔋${l.battery ?? "?"}%` : esc(e.id);
    return `<li class="emp ${e.active ? "" : "inactive"}" data-id="${esc(e.id)}">
      <div class="avatar" style="background:${STATUS[st].color}">${esc(initials(e.name))}</div>
      <div><div class="emp-name">${esc(e.name)}</div><div class="emp-sub">${esc(e.id)} · ${sub}</div></div>
      <span class="badge s-${st}">${STATUS[st].label}</span></li>`;
  }).join("") || `<li class="empty">Koi match nahi mila</li>`;
}

$("empList").addEventListener("click", (e) => {
  const li = e.target.closest("[data-id]");
  if (li) selectEmp(li.dataset.id);
});
$("search").addEventListener("input", renderList);
$("showAllBtn").onclick = () => { needFit = true; renderMarkers(); };

// ---------- detail ----------
function selectEmp(id) {
  if (!employees.has(id)) return;
  selected = id;
  map.closePopup();
  $("listView").classList.add("hidden");
  $("detailView").classList.remove("hidden");
  $("dDate").value = todayStr();
  renderDetail();
  renderMarkers();
  loadDay();
}

$("backBtn").onclick = () => {
  selected = null;
  dayPoints = [];
  historyLayer.clearLayers();
  $("detailView").classList.add("hidden");
  $("listView").classList.remove("hidden");
  needFit = true;
  renderAll();
};

function renderDetail() {
  const emp = employees.get(selected);
  if (!emp) { $("backBtn").onclick(); return; }
  const st = statusOf(emp), l = live.get(selected);
  $("dName").textContent = emp.name;
  $("dMobile").textContent = `📱 ${emp.id} · PIN ${emp.pin}`;
  $("dBadge").className = `badge s-${st}`;
  $("dBadge").textContent = STATUS[st].label;
  $("dLive").innerHTML = l
    ? `Last contact: <b>${timeAgo(toDate(l.seenAt))}</b> · 🔋 ${l.battery ?? "?"}%${l.charging ? " 🔌" : ""}<br>
       <span class="muted">${esc(l.device || "")} · App v${esc(l.appVersion || "?")}</span>`
    : `<span class="muted">Employee ne abhi tak app me login nahi kiya.</span>`;
  $("toggleBtn").textContent = emp.active ? "⏸️ Band karein" : "▶️ Chalu karein";
}

$("dDate").addEventListener("change", () => loadDay(true));
$("liveBtn").onclick = () => {
  const l = live.get(selected);
  if (l && typeof l.lat === "number") map.setView([l.lat, l.lng], 16);
  else alertModal("Is employee ki live location abhi available nahi hai.");
};

async function loadDay(fit = true) {
  const id = selected;
  if (!id) return;
  const [y, m, d] = $("dDate").value.split("-").map(Number);
  const start = new Date(y, m - 1, d), end = new Date(y, m - 1, d + 1);
  $("dayStats").innerHTML = `<div class="stat" style="grid-column:1/-1"><span>Load ho raha hai…</span></div>`;
  $("timeline").innerHTML = "";

  let snap;
  try {
    snap = await getDocs(query(
      collection(db, "employees", id, "locations"),
      where("t", ">=", Timestamp.fromDate(start)),
      where("t", "<", Timestamp.fromDate(end)),
      orderBy("t"),
    ));
  } catch (err) { onDbError(err); return; }
  if (selected !== id) return;

  dayPoints = snap.docs.map((x) => { const v = x.data(); return { ...v, t: toDate(v.t) }; });
  drawDay(fit);
}

function detectStops(pts, radius = 100, minMs = 10 * 60000) {
  const stops = [];
  let i = 0;
  while (i < pts.length) {
    let j = i;
    while (j + 1 < pts.length && distM(pts[i], pts[j + 1]) <= radius) j++;
    if (pts[j].t - pts[i].t >= minMs) {
      const grp = pts.slice(i, j + 1);
      stops.push({
        lat: grp.reduce((s, p) => s + p.lat, 0) / grp.length,
        lng: grp.reduce((s, p) => s + p.lng, 0) / grp.length,
        from: pts[i].t, to: pts[j].t,
      });
      i = j + 1;
    } else i++;
  }
  return stops;
}

function drawDay(fit = true) {
  historyLayer.clearLayers();
  const pts = dayPoints;
  const emp = employees.get(selected);
  if (!pts.length) {
    $("dayStats").innerHTML = `<div class="stat" style="grid-column:1/-1"><span>Is din ki koi location record nahi hai</span></div>`;
    $("timeline").innerHTML = "";
    const l = live.get(selected);
    if (fit && l && typeof l.lat === "number") map.setView([l.lat, l.lng], 14);
    return;
  }

  // distance (galat GPS jumps ignore)
  let dist = 0;
  for (let k = 1; k < pts.length; k++) {
    const a = pts[k - 1], b = pts[k];
    if ((a.acc ?? 0) > 100 || (b.acc ?? 0) > 100) continue;
    const dm = distM(a, b), hrs = (b.t - a.t) / 3600000;
    if (hrs > 0 && dm / 1000 / hrs > 150) continue; // 150 km/h se zyada = jump
    dist += dm;
  }
  const stops = detectStops(pts);
  const first = pts[0], last = pts[pts.length - 1];

  const line = L.polyline(pts.map((p) => [p.lat, p.lng]), { color: "#1e5eff", weight: 4, opacity: 0.8 }).addTo(historyLayer);
  pts.forEach((p) => L.circleMarker([p.lat, p.lng], { radius: 3, color: "#1e5eff", weight: 1, fillOpacity: 0.9 })
    .bindTooltip(`${fmtTime(p.t)} · 🔋${p.battery ?? "?"}%`).addTo(historyLayer));
  L.marker([first.lat, first.lng], { icon: dotIcon("#16a34a") }).bindTooltip(`Shuru: ${fmtTime(first.t)}`).addTo(historyLayer);
  L.marker([last.lat, last.lng], { icon: dotIcon("#dc2626") }).bindTooltip(`Aakhri: ${fmtTime(last.t)}`).addTo(historyLayer);
  stops.forEach((s, n) => L.marker([s.lat, s.lng], {
    icon: L.divIcon({ className: "", html: `<div class="stopdot">${n + 1}</div>`, iconSize: [22, 22], iconAnchor: [11, 11] }),
  }).bindPopup(`<b>Stop ${n + 1}</b><br>${fmtTime(s.from)} – ${fmtTime(s.to)}<br>Ruka: ${fmtDur(s.to - s.from)}`).addTo(historyLayer));
  if (fit) map.fitBounds(line.getBounds(), { padding: [40, 40], maxZoom: 16 });

  const stat = (v, l) => `<div class="stat"><b>${v}</b><span>${l}</span></div>`;
  $("dayStats").innerHTML =
    stat(`${(dist / 1000).toFixed(1)} km`, "Kul doori") +
    stat(stops.length, "Stops (10+ min)") +
    stat(`${Math.floor((last.t - first.t) / 3600000)}h ${Math.round(((last.t - first.t) % 3600000) / 60000)}m`, "Duty time") +
    stat(fmtTime(first.t), "Pehli location") +
    stat(fmtTime(last.t), "Aakhri location") +
    stat(pts.length, "Points");

  const items = [
    { cls: "start", p: first, text: `🟢 ${fmtTime(first.t)} — Din ki shuruaat` },
    ...stops.map((s, n) => ({ cls: "stop", p: s, text: `🟠 Stop ${n + 1}: ${fmtTime(s.from)} – ${fmtTime(s.to)} (${fmtDur(s.to - s.from)})` })),
    { cls: "end", p: last, text: `🔴 ${fmtTime(last.t)} — Aakhri location` },
  ];
  $("timeline").innerHTML = items.map((it, k) =>
    `<li class="${it.cls}" data-k="${k}">${it.text}<div class="addr"></div></li>`).join("");
  $("timeline").querySelectorAll("li").forEach((li) => {
    li.onclick = async () => {
      const p = items[+li.dataset.k].p;
      map.setView([p.lat, p.lng], 17);
      const box = li.querySelector(".addr");
      box.textContent = "Address dhoondh rahe hain…";
      box.textContent = await reverseGeocode(p.lat, p.lng);
    };
  });
  $("csvBtn").onclick = () => downloadCsv(emp, pts);
}

async function reverseGeocode(lat, lng) {
  const key = `${lat.toFixed(4)},${lng.toFixed(4)}`;
  if (addrCache.has(key)) return addrCache.get(key);
  try {
    const r = await fetch(`https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&lat=${lat}&lon=${lng}`);
    const j = await r.json();
    const a = (j.display_name || "").split(",").slice(0, 4).join(",");
    addrCache.set(key, a || key);
    return a || key;
  } catch { return `${lat.toFixed(5)}, ${lng.toFixed(5)}`; }
}

function downloadCsv(emp, pts) {
  const rows = [["time", "latitude", "longitude", "accuracy_m", "speed_kmh", "battery"]];
  pts.forEach((p) => rows.push([p.t.toLocaleString("en-IN"), p.lat, p.lng, Math.round(p.acc || 0),
    ((p.speed || 0) * 3.6).toFixed(1), p.battery ?? ""]));
  const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(",")).join("\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
  a.download = `${(emp?.name || selected).replace(/\s+/g, "_")}_${$("dDate").value}.csv`;
  a.click();
}

// ---------- modal ----------
function openModal(html) { $("modalBody").innerHTML = html; $("modal").classList.remove("hidden"); }
function closeModal() { $("modal").classList.add("hidden"); $("modalBody").innerHTML = ""; }
$("modal").addEventListener("click", (e) => { if (e.target.id === "modal" || e.target.dataset.close !== undefined) closeModal(); });
function alertModal(msg) { openModal(`<p>${esc(msg)}</p><button class="btn primary" data-close>Theek hai</button>`); }

function confirmModal(msg, okText = "Haan") {
  return new Promise((resolve) => {
    openModal(`<p>${msg}</p><div class="btn-row"><button class="btn danger" id="cOk">${esc(okText)}</button>
      <button class="btn" id="cNo">Cancel</button></div>`);
    $("cOk").onclick = () => { closeModal(); resolve(true); };
    $("cNo").onclick = () => { closeModal(); resolve(false); };
  });
}

function shareText(emp) {
  return `Namaste ${emp.name},\n\nField Tracker app install karein:\n${APK_URL}\n\nLogin:\nMobile: ${emp.id}\nPIN: ${emp.pin}\n\n` +
    `Login ke baad app me saari settings ✓ karein (Location: "Allow all the time", Battery: No restrictions).`;
}

function showCredentials(emp, title = "Employee ban gaya ✅") {
  openModal(`
    <h3>${esc(title)}</h3>
    <div class="cred">👤 <b>${esc(emp.name)}</b><br>📱 Mobile: <b>${esc(emp.id)}</b><br>🔑 PIN: <b>${esc(emp.pin)}</b></div>
    <p class="small muted">Ye details aur APK link employee ko bhejein.</p>
    <div class="btn-row">
      <a class="btn primary" target="_blank" rel="noopener"
         href="https://wa.me/91${esc(emp.id)}?text=${encodeURIComponent(shareText(emp))}">WhatsApp par bhejein</a>
      <button class="btn" id="copyBtn">Copy</button>
      <button class="btn ghost" data-close>Band karein</button>
    </div>`);
  $("copyBtn").onclick = async () => {
    try { await navigator.clipboard.writeText(shareText(emp)); $("copyBtn").textContent = "Copied ✓"; } catch { /* ignore */ }
  };
}

// ---------- create employee ----------
$("addBtn").onclick = () => {
  openModal(`
    <h3>Naya employee</h3>
    <form id="addForm" style="display:grid;gap:12px">
      <label>Naam<input id="fName" required maxlength="60" placeholder="Jaise: Ramesh Kumar"></label>
      <label>Mobile number<input id="fMobile" required inputmode="numeric" maxlength="13" placeholder="10 digit"></label>
      <label>PIN (app login ke liye)<input id="fPin" required inputmode="numeric" maxlength="8" value="${randomPin()}"></label>
      <p id="fErr" class="err"></p>
      <div class="btn-row"><button class="btn primary" type="submit" id="fSave">Save</button>
      <button class="btn" type="button" data-close>Cancel</button></div>
    </form>`);
  $("fName").focus();
  $("addForm").onsubmit = async (e) => {
    e.preventDefault();
    const name = $("fName").value.trim();
    const mobile = $("fMobile").value.replace(/\D/g, "").slice(-10);
    const pin = $("fPin").value.trim();
    if (!name) return ($("fErr").textContent = "Naam daalein");
    if (!/^\d{10}$/.test(mobile)) return ($("fErr").textContent = "Sahi 10 digit mobile number daalein");
    if (!/^\d{4,8}$/.test(pin)) return ($("fErr").textContent = "PIN 4 se 8 digit ka number ho");
    $("fSave").disabled = true;
    try {
      if ((await getDoc(doc(db, "employees", mobile))).exists()) {
        $("fSave").disabled = false;
        return ($("fErr").textContent = "Is mobile number ka employee pehle se hai");
      }
      const b = writeBatch(db);
      b.set(doc(db, "employees", mobile), { name, mobile, pin, active: true, createdAt: serverTimestamp() });
      b.set(doc(db, "logins", `${mobile}_${pin}`), { mobile, createdAt: serverTimestamp() });
      await b.commit();
      showCredentials({ id: mobile, name, pin });
    } catch (err) {
      $("fSave").disabled = false;
      $("fErr").textContent = err.message;
    }
  };
};

// ---------- employee actions ----------
$("shareBtn").onclick = () => showCredentials(employees.get(selected), "Login details");

$("editBtn").onclick = () => {
  const emp = employees.get(selected);
  openModal(`<h3>Naam badlein</h3><input id="eName" maxlength="60" value="${esc(emp.name)}">
    <div class="btn-row"><button class="btn primary" id="eSave">Save</button><button class="btn" data-close>Cancel</button></div>`);
  $("eSave").onclick = async () => {
    const name = $("eName").value.trim();
    if (!name) return;
    await updateDoc(doc(db, "employees", emp.id), { name });
    closeModal();
  };
};

$("pinBtn").onclick = () => {
  const emp = employees.get(selected);
  openModal(`<h3>Naya PIN</h3>
    <p class="small muted">Jo phone pehle se login hai wo chalta rahega. Naya PIN sirf naye login ke liye hai.</p>
    <input id="pNew" inputmode="numeric" maxlength="8" value="${randomPin()}">
    <div class="btn-row"><button class="btn primary" id="pSave">Save</button><button class="btn" data-close>Cancel</button></div>`);
  $("pSave").onclick = async () => {
    const pin = $("pNew").value.trim();
    if (!/^\d{4,8}$/.test(pin)) return;
    const b = writeBatch(db);
    b.delete(doc(db, "logins", `${emp.id}_${emp.pin}`));
    if (emp.active) b.set(doc(db, "logins", `${emp.id}_${pin}`), { mobile: emp.id, createdAt: serverTimestamp() });
    b.update(doc(db, "employees", emp.id), { pin });
    await b.commit();
    showCredentials({ ...emp, pin }, "PIN badal gaya ✅");
  };
};

$("toggleBtn").onclick = async () => {
  const emp = employees.get(selected);
  if (emp.active) {
    const ok = await confirmModal(`<b>${esc(emp.name)}</b> ki tracking band karein? Phone se app logout ho jayega aur login nahi ho payega.`, "Band karein");
    if (!ok) return;
    const b = writeBatch(db);
    b.update(doc(db, "employees", emp.id), { active: false });
    b.delete(doc(db, "logins", `${emp.id}_${emp.pin}`));
    await b.commit();
  } else {
    const b = writeBatch(db);
    b.update(doc(db, "employees", emp.id), { active: true });
    b.set(doc(db, "logins", `${emp.id}_${emp.pin}`), { mobile: emp.id, createdAt: serverTimestamp() });
    await b.commit();
    showCredentials(emp, "Account chalu ✅ — employee ko dobara login karna hoga");
  }
};

$("delBtn").onclick = async () => {
  const emp = employees.get(selected);
  const ok = await confirmModal(`<b>${esc(emp.name)}</b> (${esc(emp.id)}) ko <b>hamesha ke liye delete</b> karein? Saari location history bhi mit jayegi.`, "Delete");
  if (!ok) return;
  openModal(`<p>Delete ho raha hai… kripya ruke</p>`);
  try {
    const b = writeBatch(db);
    b.delete(doc(db, "employees", emp.id));
    b.delete(doc(db, "logins", `${emp.id}_${emp.pin}`));
    b.delete(doc(db, "live", emp.id));
    const sess = await getDocs(query(collection(db, "sessions"), where("mobile", "==", emp.id)));
    sess.forEach((s) => b.delete(s.ref));
    await b.commit();
    // history 400-400 karke delete
    for (;;) {
      const s = await getDocs(query(collection(db, "employees", emp.id, "locations"), limit(400)));
      if (s.empty) break;
      const bb = writeBatch(db);
      s.forEach((d) => bb.delete(d.ref));
      await bb.commit();
    }
    closeModal();
  } catch (err) {
    alertModal("Error: " + err.message);
  }
};

// har 30 sec "x min pehle" aur status refresh; aaj ka route har 2 min reload
let tick = 0;
setInterval(() => {
  if (!auth.currentUser) return;
  renderAll();
  tick++;
  if (selected && $("dDate").value === todayStr() && tick % 4 === 0) loadDay(false);
}, 30000);

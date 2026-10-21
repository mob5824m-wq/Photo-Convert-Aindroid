(() => {
  const $ = (id) => document.getElementById(id);

  let state = {
    status: null,
    incomingFiles: [],
    outputFiles: [],
    quarantineItems: [],
    history: [],
    formDirty: false,
  };

  function formatBytes(bytes) {
    if (!bytes || bytes === 0) return "0 B";
    const sign = bytes < 0 ? "-" : "";
    const abs = Math.abs(bytes);
    const units = ["B", "KB", "MB", "GB", "TB"];
    const i = Math.min(units.length - 1, Math.floor(Math.log(abs) / Math.log(1024)));
    const val = abs / Math.pow(1024, i);
    return `${sign}${val >= 10 || i === 0 ? val.toFixed(1) : val.toFixed(2)} ${units[i]}`;
  }

  function escapeHtml(str) {
    return String(str ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function toast(msg) {
    const el = document.createElement("div");
    el.className = "toast";
    el.textContent = msg;
    $("toastContainer").appendChild(el);
    setTimeout(() => el.remove(), 3600);
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, opts);
    if (!res.ok) {
      const txt = await res.text();
      throw new Error(txt || `HTTP ${res.status}`);
    }
    return res.json();
  }

  function switchTab(tabName) {
    document.querySelectorAll(".tab-btn").forEach((b) => {
      const active = b.dataset.tab === tabName;
      b.classList.toggle("active", active);
      b.setAttribute("aria-selected", active ? "true" : "false");
    });
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));
    const panel = $(`tab-${tabName}`);
    if (panel) panel.classList.add("active");
  }

  // Theme Toggle
  const savedTheme = localStorage.getItem("pixelnas_theme") || "light";
  document.documentElement.setAttribute("data-theme", savedTheme);
  $("themeToggleBtn").addEventListener("click", () => {
    const next = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    localStorage.setItem("pixelnas_theme", next);
  });

  // Tabs
  document.querySelectorAll(".tab-btn").forEach((btn) => {
    btn.addEventListener("click", () => switchTab(btn.dataset.tab));
  });

  function renderAll() {
    const st = state.status;
    if (!st) return;
    const cfg = st.settings;
    const worker = st.worker;
    const stats = st.stats;

    // Header & Profile
    $("deviceProfileSelect").value = cfg.device_profile;
    const prof = cfg.profile_details || {};
    $("profileSubtitle").textContent = `${prof.name || "Google Pixel"} · ${prof.google_photos_tier || "Storage Saver"}`;
    $("pixelEntitlementNote").textContent = prof.notes || "";

    const pill = $("engineStatusPill");
    if (worker.active_file || worker.queue_size > 0) {
      pill.classList.remove("paused");
      $("engineStatusText").textContent = `Converting (${worker.queue_size + (worker.active_file ? 1 : 0)})`;
      $("activeJobBar").hidden = false;
      $("activeJobText").textContent = `Processing ${worker.active_file || "queued file"}...`;
    } else if (cfg.auto_watch_enabled) {
      pill.classList.remove("paused");
      $("engineStatusText").textContent = "Online · Watching";
      $("activeJobBar").hidden = true;
    } else {
      pill.classList.add("paused");
      $("engineStatusText").textContent = "Manual Queue Mode";
      $("activeJobBar").hidden = true;
    }

    // Pipeline Banner
    $("incomingCountMetric").textContent = `${st.folders.incoming.file_count} ${st.folders.incoming.file_count === 1 ? "file" : "files"}`;
    $("incomingBytesMetric").textContent = st.folders.incoming.file_count
      ? `${formatBytes(st.folders.incoming.total_bytes)} queued`
      : "0 B — high-res cleaned";
    $("incomingPathText").textContent = st.folders.incoming.path;
    $("nomediaBadge").textContent = st.folders.incoming.nomedia_protected ? ".nomedia shielded" : "Watched";

    const profileCount = (cfg.folder_profiles || []).length;
    $("modeBadge").textContent = cfg.storage_saver_enabled ? "Storage Saver ON" : "Original Pass-Through";
    $("engineSummaryLine").textContent = cfg.storage_saver_enabled
      ? `≤${cfg.max_megapixels} MP JPEG (${cfg.jpeg_quality}%) · ${profileCount} Folder Profiles · Quarantine Guard`
      : `Original Pass-Through · ${profileCount} Folder Profiles · Quarantine Guard`;

    $("outputCountMetric").textContent = `${st.folders.output.file_count} ${st.folders.output.file_count === 1 ? "file" : "files"}`;
    $("totalSavedMetric").textContent = `${formatBytes(Math.max(0, stats.total_saved_bytes || 0))} freed`;
    $("savedPctBadge").textContent = `${stats.avg_saved_pct || 0}% saved`;
    $("outputPathText").textContent = st.folders.output.path;
    $("galleryTabCount").textContent = String(st.folders.output.file_count);

    // Quarantine Counter
    const qCount = state.quarantineItems.length;
    const qBadge = $("quarantineTabCount");
    qBadge.textContent = String(qCount);
    qBadge.classList.toggle("tab-counter-alert", qCount > 0);

    // Quick Toggles
    $("toggleStorageSaver").checked = !!cfg.storage_saver_enabled;
    $("toggleDeleteOriginals").checked = !!cfg.delete_originals;
    $("toggleCompressVideos").checked = !!cfg.compress_videos;
    $("toggleAutoWatch").checked = !!cfg.auto_watch_enabled;
    if ($("toggleSmartMetadata")) {
      $("toggleSmartMetadata").checked =
        cfg.restore_exif_from_filename !== false && cfg.merge_takeout_json !== false;
    }
    $("diskUsageNote").textContent = `Free Storage: ${formatBytes(st.storage.free_bytes)} / ${formatBytes(st.storage.total_bytes)}`;

    // Network Endpoints & QR
    $("endpointWebUrl").textContent = st.network.web_ui_url;
    if ($("endpointSmbUnc")) {
      $("endpointSmbUnc").textContent = st.network.smb_saver_unc || st.network.smb_unc || "";
    }
    if ($("endpointSmbUrl")) {
      $("endpointSmbUrl").textContent = st.network.smb_url || "";
    }
    if ($("smbBadgeStatus")) {
      $("smbBadgeStatus").textContent = st.network.smb_enabled
        ? `Active · :${st.network.smb_port}`
        : "Disabled";
    }
    $("endpointWebdavUrl").textContent = st.network.webdav_saver_url || st.network.webdav_incoming_url;
    $("endpointFtpUrl").textContent = st.network.ftp_url;
    $("ftpBadgeStatus").textContent = st.network.ftp_enabled ? `Active · :${st.network.ftp_port}` : "Disabled";
    $("syncIncomingInline").textContent = `${st.folders.incoming.path}/StorageSaver`;
    $("syncOutputInline").textContent = st.folders.output.path;
    const qrSrc = `/api/qrcode?text=${encodeURIComponent(st.network.web_ui_url)}`;
    if ($("portalQrImg").dataset.src !== qrSrc) {
      $("portalQrImg").src = qrSrc;
      $("portalQrImg").dataset.src = qrSrc;
    }

    // Settings Form
    if (!state.formDirty) {
      $("inputIncomingDir").value = cfg.incoming_dir;
      $("inputOutputDir").value = cfg.output_dir;
      $("inputMaxMp").value = cfg.max_megapixels;
      $("labelMaxMp").textContent = `${Number(cfg.max_megapixels).toFixed(1)} MP`;
      $("inputJpegQuality").value = cfg.jpeg_quality;
      $("labelJpegQuality").textContent = `${cfg.jpeg_quality}%`;
      $("inputVideoRes").value = String(cfg.max_video_short_side);
      if ($("inputSmbPort")) {
        $("inputSmbPort").value = cfg.smb_port || 4445;
      }
      if ($("inputSmbShareName")) {
        $("inputSmbShareName").value = cfg.smb_share_name || "PixelNAS";
      }
      $("inputFtpPort").value = cfg.ftp_port;
      $("inputAuthEnabled").checked = !!cfg.auth_enabled;
      $("inputAuthUser").value = cfg.auth_username || "pixel";
      $("inputAuthPass").value = cfg.auth_password || "";
      $("inputThermal").value = cfg.thermal_limit_celsius;
      if ($("inputRetentionDays")) {
        $("inputRetentionDays").value = String(cfg.auto_purge_output_days || 0);
      }
      if ($("inputMinFreeGb")) {
        $("inputMinFreeGb").value = cfg.min_free_storage_gb || 0;
      }
    }

    renderFolderProfiles(cfg.folder_profiles || []);
    renderFileLists();
    renderHistory();
    renderGallery();
    renderQuarantine();
  }

  function renderFolderProfiles(profiles) {
    const sel = $("uploadProfileSelect");
    const prevVal = sel.value;
    sel.innerHTML =
      `<option value="">Global Default (/Incoming)</option>` +
      profiles
        .map((p) => {
          const fname = p.folder_name || p.name || "";
          const isSaver =
            typeof p.storage_saver_enabled === "boolean"
              ? p.storage_saver_enabled
              : p.mode !== "original_passthrough";
          const modeTxt = isSaver
            ? `≤${p.max_megapixels || 16} MP Saver`
            : "100% Original";
          const subTxt = p.output_subfolder ? ` → Output/${p.output_subfolder}` : "";
          return `<option value="${escapeHtml(fname)}">${escapeHtml(fname)} (/Incoming/${escapeHtml(fname)} · ${modeTxt}${escapeHtml(subTxt)})</option>`;
        })
        .join("");
    if (Array.from(sel.options).some((o) => o.value === prevVal)) {
      sel.value = prevVal;
    }

    const listEl = $("profileRulesList");
    if (!profiles.length) {
      listEl.innerHTML = `<div class="empty-state">No per-folder profile overrides active.</div>`;
      return;
    }
    listEl.innerHTML = profiles
      .map((p, idx) => {
        const fname = p.folder_name || p.name || "";
        const isSaver =
          typeof p.storage_saver_enabled === "boolean"
            ? p.storage_saver_enabled
            : p.mode !== "original_passthrough";
        const badge = isSaver
          ? `<span class="badge badge-accent">Storage Saver ≤${p.max_megapixels || 16} MP</span>`
          : `<span class="badge badge-success">Original 100%</span>`;
        const dest = p.output_subfolder
          ? `DCIM/PixelNAS/${escapeHtml(p.output_subfolder)}`
          : `DCIM/PixelNAS`;
        return `
          <div class="profile-rule-card">
            <div class="profile-rule-top">
              <span class="profile-rule-path">/Incoming/${escapeHtml(fname)}/</span>
              ${badge}
            </div>
            <div class="profile-rule-desc">
              Routes to <code>${dest}</code>
              ${p.compress_videos !== false && isSaver ? " · 1080p Video" : " · Direct Video"}
            </div>
            <div>
              <button type="button" class="btn btn-ghost btn-sm remove-profile-btn" data-profile-idx="${idx}">Remove Rule</button>
            </div>
          </div>
        `;
      })
      .join("");
  }

  function renderFileLists() {
    const incEl = $("incomingFileList");
    if (!state.incomingFiles.length) {
      incEl.innerHTML = `<div class="empty-state">Incoming upload folder is clean — high-res originals have been converted &amp; deleted.</div>`;
    } else {
      incEl.innerHTML = state.incomingFiles
        .map(
          (f, idx) => `
          <div class="file-row" data-folder="incoming" data-idx="${idx}">
            <img class="file-thumb" src="${escapeHtml(f.thumb_url)}" alt="${escapeHtml(f.name)}" loading="lazy" />
            <div class="file-meta">
              <div class="file-name">${escapeHtml(f.rel_path || f.name)}</div>
              <div class="file-sub">
                <span>${formatBytes(f.size_bytes)}</span>
                ${f.width ? `<span>${f.width}×${f.height} (${f.megapixels} MP)</span>` : ""}
                ${f.is_processing ? `<span class="badge badge-accent">Converting...</span>` : `<span class="badge badge-neutral">Waiting</span>`}
              </div>
            </div>
          </div>`
        )
        .join("");
    }

    const outEl = $("outputFileList");
    if (!state.outputFiles.length) {
      outEl.innerHTML = `<div class="empty-state">No converted photos in Output yet. Drop files or click <strong>Drop Photos + Takeout + 4K Video</strong> above!</div>`;
    } else {
      outEl.innerHTML = state.outputFiles
        .map((f, idx) => {
          const conv = f.conversion;
          const profileTag =
            conv && conv.profile_name && conv.profile_name !== "default"
              ? `<span class="badge badge-accent">${escapeHtml(conv.profile_name)}</span>`
              : "";
          const savedBadge =
            conv && conv.saved_bytes > 0
              ? `<span class="badge badge-success">-${conv.saved_pct}% (${formatBytes(conv.saved_bytes)} saved)</span>`
              : `<span class="badge badge-neutral">${conv && conv.mode === "original_passthrough" ? "Original Kept" : "Ready"}</span>`;
          return `
          <div class="file-row" data-folder="output" data-idx="${idx}">
            <img class="file-thumb" src="${escapeHtml(f.thumb_url)}" alt="${escapeHtml(f.name)}" loading="lazy" />
            <div class="file-meta">
              <div class="file-name">${escapeHtml(f.rel_path || f.name)}</div>
              <div class="file-sub">
                <span>${formatBytes(f.size_bytes)}</span>
                ${f.width ? `<span>${f.width}×${f.height} (${f.megapixels} MP)</span>` : ""}
                ${profileTag}
                ${savedBadge}
              </div>
            </div>
          </div>`;
        })
        .join("");
    }
  }

  function renderHistory() {
    const tbody = $("historyTableBody");
    if (!state.history.length) {
      tbody.innerHTML = `<tr><td colspan="7" class="empty-cell">No conversions logged yet.</td></tr>`;
      return;
    }
    tbody.innerHTML = state.history
      .map((h) => {
        const profBadge =
          h.profile_name && h.profile_name !== "default"
            ? `<span class="badge badge-neutral">${escapeHtml(h.profile_name)}</span> `
            : "";
        const modeLabel =
          h.mode === "storage_saver"
            ? `${profBadge}<span class="badge badge-accent">Storage Saver</span>`
            : h.mode === "duplicate_skipped"
            ? `${profBadge}<span class="badge badge-neutral">Duplicate Skipped</span>`
            : h.mode === "quarantined"
            ? `<span class="badge" style="color:var(--danger)">Quarantined</span>`
            : h.mode === "error"
            ? `<span class="badge" style="color:var(--danger)">Error</span>`
            : `${profBadge}<span class="badge badge-success">Original</span>`;
        const metaParts = [];
        if (h.metadata_note) {
          metaParts.push(`<span class="badge badge-accent">${escapeHtml(h.metadata_note)}</span>`);
        }
        if (h.date_taken) metaParts.push("Date");
        if (h.gps_coords) metaParts.push("GPS");
        if (h.camera_model) metaParts.push(escapeHtml(h.camera_model));
        return `
        <tr>
          <td><strong>${escapeHtml(h.output_filename || h.original_filename)}</strong></td>
          <td>${modeLabel}</td>
          <td>${h.orig_width ? `${h.orig_width}×${h.orig_height} (${h.orig_mp} MP) · ` : ""}${formatBytes(h.orig_bytes)}</td>
          <td>${h.out_width ? `${h.out_width}×${h.out_height} (${h.out_mp} MP) · ` : ""}${formatBytes(h.out_bytes)}</td>
          <td><span class="badge badge-success">${h.saved_bytes > 0 ? `-${h.saved_pct}% (${formatBytes(h.saved_bytes)})` : "0 B (Untouched)"}</span></td>
          <td>${metaParts.length ? metaParts.join(" · ") : (h.exif_preserved ? "Timestamps Kept" : escapeHtml(h.error_message || "—"))}</td>
          <td>${h.original_deleted ? "✓ Deleted" : "Isolated"}</td>
        </tr>`;
      })
      .join("");
  }

  function renderGallery() {
    const grid = $("galleryGrid");
    if (!state.outputFiles.length) {
      grid.innerHTML = `<div class="empty-state">No processed photos or videos in Output folder yet.</div>`;
      return;
    }
    grid.innerHTML = state.outputFiles
      .map((f, idx) => {
        const conv = f.conversion;
        return `
        <div class="gallery-card" data-folder="output" data-idx="${idx}">
          <img class="gallery-thumb" src="${escapeHtml(f.thumb_url)}" alt="${escapeHtml(f.name)}" loading="lazy" />
          <div class="gallery-card-body">
            <div class="gallery-card-title">${escapeHtml(f.rel_path || f.name)}</div>
            <div class="gallery-card-stats">
              <span>${f.width ? `${f.width}×${f.height}` : f.media_type.toUpperCase()} · ${formatBytes(f.size_bytes)}</span>
              ${conv && conv.saved_bytes > 0 ? `<span class="badge badge-success">-${conv.saved_pct}%</span>` : ""}
            </div>
          </div>
        </div>`;
      })
      .join("");
  }

  function renderQuarantine() {
    const el = $("quarantineList");
    if (!state.quarantineItems.length) {
      el.innerHTML = `<div class="empty-state">No corrupted transfers in Quarantine — pipeline is 100% healthy!</div>`;
      return;
    }
    el.innerHTML = state.quarantineItems
      .map(
        (q) => `
        <div class="quarantine-row">
          <div class="file-meta">
            <div class="file-name">${escapeHtml(q.name)}</div>
            <div class="file-sub">
              <span>Original path: <code>Incoming/${escapeHtml(q.original_rel_path)}</code></span>
              <span>· ${formatBytes(q.size_bytes)}</span>
              <span>· ${q.attempts} failed attempts</span>
            </div>
            <div class="file-sub" style="color:var(--danger);margin-top:3px;">
              Reason: ${escapeHtml(q.error_reason)}
            </div>
          </div>
          <div class="quarantine-actions">
            <button class="btn btn-primary btn-sm retry-quarantine-btn" data-qname="${escapeHtml(q.name)}">Retry</button>
            <a class="btn btn-secondary btn-sm" href="${escapeHtml(q.download_url)}" download="${escapeHtml(q.name)}">Inspect Bytes</a>
            <button class="btn btn-danger btn-sm delete-quarantine-btn" data-qname="${escapeHtml(q.name)}">Delete</button>
          </div>
        </div>
      `
      )
      .join("");
  }

  function openInspector(folder, idx) {
    const list = folder === "incoming" ? state.incomingFiles : state.outputFiles;
    const item = list[idx];
    if (!item) return;
    const conv = item.conversion;
    const relPath = item.rel_path || item.name;
    $("modalTitle").textContent = relPath;
    const mediaPreview =
      item.media_type === "video"
        ? `<video class="inspector-preview" src="${escapeHtml(item.media_url)}" controls preload="metadata"></video>`
        : `<img class="inspector-preview" src="${escapeHtml(item.media_url)}" alt="${escapeHtml(item.name)}" />`;

    $("modalBody").innerHTML = `
      ${mediaPreview}
      <div class="inspector-grid">
        <div class="inspector-stat">
          <span>Current File Size (${folder === "output" ? "Folder 2 Output" : "Folder 1 Incoming"})</span>
          <strong>${formatBytes(item.size_bytes)}${conv && conv.profile_name ? ` · Profile: ${escapeHtml(conv.profile_name)}` : ""}</strong>
        </div>
        <div class="inspector-stat">
          <span>Output Resolution</span>
          <strong>${item.width ? `${item.width} × ${item.height} (${item.megapixels} MP)` : "N/A"}</strong>
        </div>
        ${
          conv
            ? `
        <div class="inspector-stat">
          <span>Original Upload (Before Conversion)</span>
          <strong>${conv.orig_width ? `${conv.orig_width} × ${conv.orig_height} (${conv.orig_mp} MP) · ` : ""}${formatBytes(conv.orig_bytes)}</strong>
        </div>
        <div class="inspector-stat">
          <span>Space Freed &amp; High-Res Cleanup</span>
          <strong>${conv.saved_pct}% smaller (${formatBytes(conv.saved_bytes)} saved) · ${conv.original_deleted ? "Original Deleted ✓" : "Original Kept"}</strong>
        </div>`
            : ""
        }
        <div class="inspector-stat">
          <span>EXIF Camera Make &amp; Model</span>
          <strong>${escapeHtml(item.camera_model || (conv && conv.camera_model) || "Not embedded")}</strong>
        </div>
        <div class="inspector-stat">
          <span>EXIF Date Taken &amp; GPS Location</span>
          <strong>${escapeHtml(item.date_taken || "File timestamp")} ${item.gps_coords ? `· GPS (${escapeHtml(item.gps_coords)})` : ""}</strong>
        </div>
      </div>
      <div class="modal-actions">
        ${
          folder === "incoming"
            ? `<button class="btn btn-primary" id="modalConvertOneBtn">Convert &amp; Move Now</button>`
            : ""
        }
        <a class="btn btn-secondary" href="${escapeHtml(item.media_url)}" download="${escapeHtml(item.name)}">Download File</a>
        <button class="btn btn-danger" id="modalDeleteBtn">Delete File</button>
      </div>
    `;
    $("inspectorModal").hidden = false;

    const delBtn = $("modalDeleteBtn");
    if (delBtn) {
      delBtn.onclick = async () => {
        const encPath = relPath.split("/").map(encodeURIComponent).join("/");
        await api(`/api/files/${folder}/${encPath}`, { method: "DELETE" });
        $("inspectorModal").hidden = true;
        toast(`Deleted ${relPath}`);
        await refreshData();
      };
    }
    const convBtn = $("modalConvertOneBtn");
    if (convBtn) {
      convBtn.onclick = async () => {
        convBtn.disabled = true;
        convBtn.textContent = "Converting...";
        await api("/api/process", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ filename: relPath }),
        });
        $("inspectorModal").hidden = true;
        toast(`Processed ${relPath}`);
        await refreshData();
      };
    }
  }

  document.body.addEventListener("click", async (e) => {
    const row = e.target.closest(".file-row, .gallery-card");
    if (row) {
      openInspector(row.dataset.folder, Number(row.dataset.idx));
      return;
    }
    const copyBtn = e.target.closest(".copy-btn");
    if (copyBtn) {
      const txt = $(copyBtn.dataset.copyTarget).textContent;
      navigator.clipboard.writeText(txt).then(() => toast("Copied URL to clipboard"));
      return;
    }
    const remProfBtn = e.target.closest(".remove-profile-btn");
    if (remProfBtn && state.status) {
      const idx = Number(remProfBtn.dataset.profileIdx);
      const next = (state.status.settings.folder_profiles || []).filter((_, i) => i !== idx);
      await patchSettings({ folder_profiles: next }, "Removed folder profile rule");
      return;
    }
    const retryBtn = e.target.closest(".retry-quarantine-btn");
    if (retryBtn) {
      const qname = retryBtn.dataset.qname;
      await api("/api/quarantine/retry", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ filename: qname }),
      });
      toast(`Moved ${qname} back to Incoming for retry`);
      await refreshData();
      return;
    }
    const delQBtn = e.target.closest(".delete-quarantine-btn");
    if (delQBtn) {
      const qname = delQBtn.dataset.qname;
      await api(`/api/quarantine/${encodeURIComponent(qname)}`, { method: "DELETE" });
      toast(`Deleted ${qname} from Quarantine`);
      await refreshData();
    }
  });

  $("closeModalBtn").addEventListener("click", () => ($("inspectorModal").hidden = true));
  $("inspectorModal").addEventListener("click", (e) => {
    if (e.target === $("inspectorModal")) $("inspectorModal").hidden = true;
  });

  async function patchSettings(patch, message) {
    await api("/api/settings", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(patch),
    });
    if (message) toast(message);
    await refreshData();
  }

  // Add Per-Folder Profile Rule
  $("addProfileForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!state.status) return;
    const folderRaw = $("newProfileFolder").value.trim().replace(/[^A-Za-z0-9._-]/g, "");
    if (!folderRaw) {
      toast("Enter a valid folder name");
      return;
    }
    const isSaver = $("newProfileMode").value === "saver";
    const outSub = $("newProfileOutSub").value.trim().replace(/[^A-Za-z0-9._/-]/g, "");
    const existing = (state.status.settings.folder_profiles || []).filter(
      (p) => p.folder_name.toLowerCase() !== folderRaw.toLowerCase()
    );
    existing.push({
      folder_name: folderRaw,
      storage_saver_enabled: isSaver,
      max_megapixels: 16.0,
      jpeg_quality: 85,
      compress_videos: isSaver,
      output_subfolder: outSub,
    });
    $("newProfileFolder").value = "";
    $("newProfileOutSub").value = "";
    await patchSettings(
      { folder_profiles: existing },
      `Added /Incoming/${folderRaw}/ folder rule`
    );
  });

  $("deviceProfileSelect").addEventListener("change", async (e) => {
    const profile = e.target.value;
    const saverRecommended = profile !== "pixel_1";
    await patchSettings(
      { device_profile: profile, storage_saver_enabled: saverRecommended },
      saverRecommended
        ? "Switched default to Unlimited Storage Saver mode (≤16 MP)"
        : "Switched default to Pixel 1 Original Quality mode"
    );
  });

  $("toggleStorageSaver").addEventListener("change", (e) =>
    patchSettings(
      { storage_saver_enabled: e.target.checked },
      e.target.checked ? "Global Storage Saver enabled" : "Global Original pass-through enabled"
    )
  );
  $("toggleDeleteOriginals").addEventListener("change", (e) =>
    patchSettings(
      { delete_originals: e.target.checked },
      e.target.checked ? "Auto-delete high-res originals enabled" : "Keeping source copies in Incoming"
    )
  );
  $("toggleCompressVideos").addEventListener("change", (e) =>
    patchSettings(
      { compress_videos: e.target.checked },
      e.target.checked ? "1080p video downscaling enabled" : "Videos set to direct pass-through"
    )
  );
  $("toggleAutoWatch").addEventListener("change", (e) =>
    patchSettings(
      { auto_watch_enabled: e.target.checked },
      e.target.checked ? "Auto-watch active — processing Incoming files" : "Auto-watch paused"
    )
  );
  if ($("toggleSmartMetadata")) {
    $("toggleSmartMetadata").addEventListener("change", (e) =>
      patchSettings(
        {
          skip_duplicates: e.target.checked,
          restore_exif_from_filename: e.target.checked,
          merge_takeout_json: e.target.checked,
        },
        e.target.checked
          ? "Smart EXIF Repair, Google Takeout JSON merge & SHA-256 dedup enabled"
          : "Smart EXIF Repair & SHA-256 dedup disabled"
      )
    );
  }

  // Drag & Drop + File Upload with Target Profile Routing
  const dropzone = $("uploadDropzone");
  const fileInput = $("fileInput");
  dropzone.addEventListener("click", () => fileInput.click());
  dropzone.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropzone.classList.add("dragover");
  });
  dropzone.addEventListener("dragleave", () => dropzone.classList.remove("dragover"));
  dropzone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropzone.classList.remove("dragover");
    if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files);
  });
  fileInput.addEventListener("change", () => {
    if (fileInput.files.length) uploadFiles(fileInput.files);
    fileInput.value = "";
  });

  function uploadFiles(fileList) {
    const fd = new FormData();
    Array.from(fileList).forEach((f) => fd.append("files", f));
    const chosenProfile = $("uploadProfileSelect").value || "";
    const wrap = $("uploadProgressWrap");
    const fill = $("uploadProgressFill");
    const label = $("uploadProgressLabel");
    wrap.hidden = false;
    fill.style.width = "0%";
    label.textContent = `Uploading ${fileList.length} file(s)${chosenProfile ? ` to /Incoming/${chosenProfile}` : ""}...`;

    const xhr = new XMLHttpRequest();
    const url = chosenProfile
      ? `/api/upload?profile=${encodeURIComponent(chosenProfile)}`
      : "/api/upload";
    xhr.open("POST", url);
    xhr.upload.onprogress = (ev) => {
      if (ev.lengthComputable) {
        const pct = Math.round((ev.loaded / ev.total) * 100);
        fill.style.width = `${pct}%`;
        label.textContent = `Uploading ${fileList.length} file(s)... ${pct}%`;
      }
    };
    xhr.onload = async () => {
      wrap.hidden = true;
      if (xhr.status >= 200 && xhr.status < 300) {
        toast(
          `Uploaded ${fileList.length} file(s) to Incoming${chosenProfile ? `/${chosenProfile}` : ""}`
        );
        await refreshData();
      } else {
        toast(`Upload failed: ${xhr.responseText}`);
      }
    };
    xhr.onerror = () => {
      wrap.hidden = true;
      toast("Network error while uploading");
    };
    xhr.send(fd);
  }

  async function seedDemo(includeVideo) {
    const btn = includeVideo ? $("seedAllBtn") : $("seedPhotosBtn");
    const origText = btn.textContent;
    btn.disabled = true;
    btn.textContent = "Generating Android media...";
    try {
      const res = await api("/api/demo/seed", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ include_video: includeVideo }),
      });
      toast(`Dropped ${res.count} Android camera files across StorageSaver/, Original/ & Family/!`);
      await refreshData();
    } catch (err) {
      toast(`Error: ${err.message}`);
    } finally {
      btn.disabled = false;
      btn.textContent = origText;
    }
  }

  async function triggerCorruptDemo() {
    try {
      const res = await api("/api/demo/corrupt", { method: "POST" });
      toast(`Dropped corrupted file (${res.seeded}) — isolating to Quarantine after 2 retries!`);
      switchTab("quarantine");
      setTimeout(refreshData, 600);
      setTimeout(refreshData, 1800);
    } catch (err) {
      toast(`Error: ${err.message}`);
    }
  }

  $("seedPhotosBtn").addEventListener("click", () => seedDemo(false));
  $("seedAllBtn").addEventListener("click", () => seedDemo(true));
  $("seedCorruptBtn").addEventListener("click", triggerCorruptDemo);
  $("testCorruptTabBtn").addEventListener("click", triggerCorruptDemo);

  $("retryAllQuarantineBtn").addEventListener("click", async () => {
    const res = await api("/api/quarantine/retry", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    toast(`Moved ${res.retried} file(s) back to Incoming for retry`);
    await refreshData();
  });

  $("clearQuarantineBtn").addEventListener("click", async () => {
    const res = await api("/api/quarantine/clear", { method: "POST" });
    toast(`Cleared ${res.deleted_count} file(s) from Quarantine`);
    await refreshData();
  });

  $("processNowBtn").addEventListener("click", async () => {
    const res = await api("/api/process", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    toast(`Queued ${res.queued} file(s) from Incoming`);
    await refreshData();
  });

  $("clearIncomingBtn").addEventListener("click", async () => {
    const res = await api("/api/files/incoming/clear", { method: "POST" });
    toast(`Removed ${res.deleted_count} file(s) from Incoming`);
    await refreshData();
  });

  $("clearOutputBtn").addEventListener("click", async () => {
    const res = await api("/api/files/output/clear", { method: "POST" });
    toast(`Cleared ${res.deleted_count} synced file(s) from Output`);
    await refreshData();
  });

  $("clearHistoryBtn").addEventListener("click", async () => {
    await api("/api/history", { method: "DELETE" });
    toast("Conversion log cleared");
    await refreshData();
  });

  // Settings Form
  $("settingsForm").addEventListener("input", () => {
    state.formDirty = true;
    $("labelMaxMp").textContent = `${Number($("inputMaxMp").value).toFixed(1)} MP`;
    $("labelJpegQuality").textContent = `${$("inputJpegQuality").value}%`;
  });

  $("settingsForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    state.formDirty = false;
    await patchSettings(
      {
        incoming_dir: $("inputIncomingDir").value.trim(),
        output_dir: $("inputOutputDir").value.trim(),
        max_megapixels: Number($("inputMaxMp").value),
        jpeg_quality: Number($("inputJpegQuality").value),
        max_video_short_side: Number($("inputVideoRes").value),
        smb_port: $("inputSmbPort") ? Number($("inputSmbPort").value) : 4445,
        smb_share_name: $("inputSmbShareName")
          ? $("inputSmbShareName").value.trim() || "PixelNAS"
          : "PixelNAS",
        ftp_port: Number($("inputFtpPort").value),
        auth_enabled: $("inputAuthEnabled").checked,
        auth_username: $("inputAuthUser").value.trim() || "pixel",
        auth_password: $("inputAuthPass").value,
        thermal_limit_celsius: Number($("inputThermal").value),
        auto_purge_output_days: Number($("inputRetentionDays").value || 0),
        min_free_storage_gb: Number($("inputMinFreeGb").value || 0),
      },
      "Saved NAS & Storage Saver settings"
    );
  });

  $("refreshBtn").addEventListener("click", () => refreshData());

  async function refreshData() {
    try {
      const [status, inc, out, quar, hist] = await Promise.all([
        api("/api/status"),
        api("/api/files?folder=incoming"),
        api("/api/files?folder=output"),
        api("/api/quarantine"),
        api("/api/history?limit=50"),
      ]);
      state.status = status;
      state.incomingFiles = inc.files || [];
      state.outputFiles = out.files || [];
      state.quarantineItems = quar.items || [];
      state.history = hist.items || [];
      renderAll();
    } catch (err) {
      console.error("Poll error:", err);
    }
  }

  refreshData();
  setInterval(() => {
    const fast =
      state.status &&
      (state.status.worker.active_file ||
        state.status.worker.queue_size > 0 ||
        (state.status.settings.auto_watch_enabled && state.status.folders.incoming.file_count > 0));
    if (fast || Date.now() % 4000 < 1600) {
      refreshData();
    }
  }, 1500);
})();

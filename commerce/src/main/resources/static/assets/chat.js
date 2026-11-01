// Payport Store Messages: WhatsApp-style chat. New messages every 4 seconds, emojis, files (button or drag and drop),
// voice messages, contacts and the profile picture. Everything is sent to this site only.
(function () {
  var root = document.querySelector('.wa');
  if (!root) return;
  var chatId = parseInt(root.getAttribute('data-chat-id'), 10) || 0;
  var after = parseInt(root.getAttribute('data-after'), 10) || 0;
  var log = document.querySelector('[data-log]');
  var form = document.querySelector('[data-compose]');
  var problemEl = document.querySelector('[data-problem]');
  var text = form && form.querySelector('[data-text]');
  var fileInput = form && form.querySelector('[data-file]');
  var sendBtn = form && form.querySelector('[data-send]');
  var voiceBtn = form && form.querySelector('[data-voice]');
  var busy = false;

  function scrollDown() { if (log) log.scrollTop = log.scrollHeight; }
  scrollDown();

  function problem(msg) {
    if (!problemEl) return;
    problemEl.textContent = msg || '';
    problemEl.hidden = !msg;
  }

  function el(tag, cls, txt) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (txt != null) e.textContent = txt;
    return e;
  }

  // Only links from this site (signed file links) are used for media.
  function safeUrl(u) { return typeof u === 'string' && u.charAt(0) === '/' && u.charAt(1) !== '/' ? u : null; }

  function render(m) {
    if (!log || log.querySelector('[data-id="' + m.id + '"]')) return;
    var d = el('div', 'wa-msg ' + (m.from === 'customer' ? 'out' : 'in'));
    d.setAttribute('data-id', m.id);
    var f = m.file || {};
    var url = safeUrl(f.url);
    if (m.kind === 'IMAGE' && url) { var i = el('img', 'wa-media'); i.src = url; i.alt = 'Photo'; d.appendChild(i); }
    else if (m.kind === 'VIDEO' && url) { var v = el('video', 'wa-media'); v.src = url; v.controls = true; v.preload = 'metadata'; d.appendChild(v); }
    else if (m.kind === 'AUDIO' && url) { var a = el('audio'); a.src = url; a.controls = true; a.preload = 'metadata'; d.appendChild(a); }
    else if (m.kind === 'DOCUMENT' && url) {
      var l = el('a', 'wa-doc'); l.href = url; l.appendChild(el('span', 'wa-doc-ico', '📄')); l.appendChild(el('span', null, f.name || 'Document')); d.appendChild(l);
    } else if (m.kind === 'CONTACT' && m.contact) {
      var c = el('span', 'wa-contact'); c.appendChild(el('span', 'wa-doc-ico', '👤'));
      var inner = el('span'); inner.appendChild(el('b', null, m.contact.name)); inner.appendChild(document.createElement('br'));
      var tel = el('a', null, m.contact.phone); tel.href = 'tel:' + String(m.contact.phone).replace(/[^0-9+]/g, ''); inner.appendChild(tel);
      c.appendChild(inner); d.appendChild(c);
    }
    if (m.kind === 'TEXT' || (m.kind !== 'CONTACT' && m.file && m.text !== f.name)) d.appendChild(el('span', 'wa-text', m.text));
    d.appendChild(el('small', null, String(m.time || '').slice(11)));
    log.appendChild(d);
  }

  function renderList(chats) {
    var list = document.querySelector('[data-list]');
    if (!list || !chats) return;
    chats.forEach(function (c) {
      var a = list.querySelector('a[href="/messages?c=' + c.id + '"]');
      if (!a) return;
      var prev = a.querySelector('.wa-prev'); if (prev) prev.textContent = c.preview;
      var b = a.querySelector('.wa-badge');
      if (c.unread > 0 && c.id !== chatId) {
        if (!b) { b = el('b', 'wa-badge'); a.querySelector('.wa-item-bottom').appendChild(b); }
        b.textContent = c.unread;
      } else if (b) b.remove();
    });
  }

  function poll() {
    if (document.hidden) return;
    fetch('/messages/' + chatId + '/poll?after=' + after, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d) return;
        var near = log && log.scrollHeight - log.scrollTop - log.clientHeight < 120;
        (d.messages || []).forEach(function (m) { render(m); after = Math.max(after, m.id); });
        if (near && d.messages && d.messages.length) scrollDown();
        renderList(d.chats);
      }).catch(function () {});
  }
  setInterval(poll, 4000);

  function post(url, body) {
    busy = true;
    problem('');
    return fetch(url, { method: 'POST', credentials: 'same-origin', body: body, headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (d) { if (!r.ok) throw new Error(d.message || 'Not sent. Try again.'); return d; }); })
      .then(function () { poll(); setTimeout(scrollDown, 300); })
      .catch(function (e) { problem(e.message); })
      .finally(function () { busy = false; });
  }

  // ── typing and sending text ──
  function toggleSend() {
    var has = text && text.value.trim().length > 0;
    if (sendBtn) sendBtn.hidden = !has;
    if (voiceBtn) voiceBtn.hidden = has;
  }
  if (text) {
    text.addEventListener('input', function () { toggleSend(); text.style.height = 'auto'; text.style.height = Math.min(text.scrollHeight, 140) + 'px'; });
    text.addEventListener('keydown', function (e) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit ? form.requestSubmit() : form.submit(); } });
  }
  if (form) form.addEventListener('submit', function (e) {
    e.preventDefault();
    var t = text.value.trim();
    if (!t || busy) return;
    var b = new FormData(); b.append('text', t);
    text.value = ''; toggleSend(); text.style.height = 'auto';
    post('/messages/' + chatId + '/text', b);
  });

  // ── emojis and attachments ──
  function pop(sel, show) { var p = form && form.querySelector(sel); if (p) p.hidden = show === undefined ? !p.hidden : !show; }
  document.addEventListener('click', function (e) {
    if (e.target.closest('[data-emoji-toggle]')) { pop('[data-attach-pop]', false); pop('[data-emoji-pop]'); return; }
    if (e.target.closest('[data-attach-toggle]')) { pop('[data-emoji-pop]', false); pop('[data-attach-pop]'); return; }
    var em = e.target.closest('[data-emoji]');
    if (em && text) {
      var s = text.selectionStart || text.value.length;
      text.value = text.value.slice(0, s) + em.textContent + text.value.slice(text.selectionEnd || s);
      text.focus(); toggleSend(); return;
    }
    var pick = e.target.closest('[data-pick]');
    if (pick && fileInput) { fileInput.accept = pick.getAttribute('data-pick'); fileInput.click(); pop('[data-attach-pop]', false); return; }
    if (e.target.closest('[data-contact]')) { pop('[data-attach-pop]', false); pop('[data-contact-form]', true); return; }
    if (e.target.closest('[data-contact-send]')) {
      var cf = form.querySelector('[data-contact-form]');
      var b = new FormData(); b.append('name', cf.querySelector('[name=cname]').value); b.append('phone', cf.querySelector('[name=cphone]').value);
      post('/messages/' + chatId + '/contact', b).then(function () { cf.hidden = true; cf.querySelectorAll('input').forEach(function (i) { i.value = ''; }); });
      return;
    }
    if (!e.target.closest('.wa-emoji-pop, .wa-attach-pop, .wa-contact-form')) { pop('[data-emoji-pop]', false); pop('[data-attach-pop]', false); }
  });

  function sendFiles(list, voice) {
    var files = Array.prototype.slice.call(list || [], 0, 10);
    var chain = Promise.resolve();
    files.forEach(function (f) {
      chain = chain.then(function () {
        var b = new FormData(); b.append('file', f, f.name || 'voice.webm');
        if (voice) b.append('voice', '1');
        return post('/messages/' + chatId + '/files', b);
      });
    });
    return chain;
  }
  if (fileInput) fileInput.addEventListener('change', function () { sendFiles(fileInput.files); fileInput.value = ''; });

  // drag and drop anywhere on the conversation
  var drop = document.querySelector('[data-drop]'), zone = document.querySelector('[data-dropzone]');
  if (drop && zone && chatId > 0) {
    var depth = 0;
    drop.addEventListener('dragenter', function (e) { if (e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types, 'Files') >= 0) { depth++; zone.hidden = false; } });
    drop.addEventListener('dragleave', function () { depth = Math.max(0, depth - 1); if (!depth) zone.hidden = true; });
    drop.addEventListener('dragover', function (e) { e.preventDefault(); });
    drop.addEventListener('drop', function (e) { e.preventDefault(); depth = 0; zone.hidden = true; sendFiles(e.dataTransfer.files); });
  }
  // pasting a picture sends it too
  if (text) text.addEventListener('paste', function (e) {
    var items = (e.clipboardData && e.clipboardData.files) || [];
    if (items.length) { e.preventDefault(); sendFiles(items); }
  });

  // ── voice messages ──
  var rec = null, chunks = [];
  if (voiceBtn) {
    if (!window.MediaRecorder || !navigator.mediaDevices) voiceBtn.hidden = true;
    voiceBtn.addEventListener('click', function () {
      if (rec && rec.state === 'recording') { rec.stop(); return; }
      navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
        chunks = [];
        rec = new MediaRecorder(stream);
        rec.ondataavailable = function (e) { if (e.data.size) chunks.push(e.data); };
        rec.onstop = function () {
          stream.getTracks().forEach(function (t) { t.stop(); });
          voiceBtn.classList.remove('rec'); voiceBtn.textContent = '🎤';
          var type = rec.mimeType || 'audio/webm';
          var ext = type.indexOf('mp4') >= 0 ? 'm4a' : type.indexOf('ogg') >= 0 ? 'ogg' : 'webm';
          var blob = new Blob(chunks, { type: type });
          if (blob.size > 0) sendFiles([new File([blob], 'voice.' + ext, { type: type })], true);
        };
        rec.start();
        voiceBtn.classList.add('rec'); voiceBtn.textContent = '■';
        setTimeout(function () { if (rec && rec.state === 'recording') rec.stop(); }, 5 * 60 * 1000);
      }).catch(function () { problem('Allow the microphone to send a voice message.'); });
    });
  }

  // ── my profile picture ──
  var av = document.querySelector('[data-avatar-input]');
  if (av) av.addEventListener('change', function () {
    if (!av.files.length) return;
    var b = new FormData(); b.append('file', av.files[0]);
    fetch('/me/avatar', { method: 'POST', credentials: 'same-origin', body: b, headers: { Accept: 'application/json' } })
      .then(function (r) { return r.json().then(function (d) { if (!r.ok) throw new Error(d.message || 'Not saved'); return d; }); })
      .then(function (d) {
        var old = document.querySelector('[data-my-avatar]');
        var url = safeUrl(d.avatar);
        if (old && url) { var img = el('img', 'wa-av'); img.src = url; img.alt = ''; img.setAttribute('data-my-avatar', ''); old.replaceWith(img); }
      })
      .catch(function (e) { alert(e.message); });
    av.value = '';
  });

  // ── search the list ──
  var search = document.querySelector('[data-search]');
  if (search) search.addEventListener('input', function () {
    var q = search.value.trim().toLowerCase();
    document.querySelectorAll('[data-list] li[data-name]').forEach(function (li) { li.hidden = q && li.getAttribute('data-name').indexOf(q) < 0; });
  });
})();

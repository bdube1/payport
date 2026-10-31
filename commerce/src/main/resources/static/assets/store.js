// Payport Store pages: copy links, one submit per form, the picture gallery, the quantity stepper, the share button
// and new chat messages. Everything works without this file too (plain links and forms).
(function () {
  function copy(b) {
    var text = b.getAttribute('data-copy');
    var done = function () { var t = b.textContent; b.textContent = 'Copied'; setTimeout(function () { b.textContent = t; }, 1500); };
    if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () {});
  }

  document.addEventListener('click', function (e) {
    var c = e.target.closest('[data-copy]');
    if (c) { copy(c); return; }

    // quantity stepper
    var s = e.target.closest('[data-step]');
    if (s) {
      var input = s.parentNode.querySelector('input');
      var v = parseInt(input.value, 10) || 1;
      v = Math.min(parseInt(input.max, 10) || 20, Math.max(parseInt(input.min, 10) || 1, v + parseInt(s.getAttribute('data-step'), 10)));
      input.value = v;
      return;
    }

    // gallery
    var g = e.target.closest('[data-gallery]');
    if (g) {
      var thumbs = g.querySelectorAll('.thumb');
      var at = 0;
      for (var i = 0; i < thumbs.length; i++) if (thumbs[i].classList.contains('on')) at = i;
      var to = -1;
      var t = e.target.closest('.thumb');
      if (t) to = parseInt(t.getAttribute('data-i'), 10);
      else if (e.target.closest('[data-next]')) to = (at + 1) % thumbs.length;
      else if (e.target.closest('[data-prev]')) to = (at - 1 + thumbs.length) % thumbs.length;
      if (to >= 0 && thumbs[to]) show(g, thumbs, to);
    }

    // share: the phone's own share sheet when there is one, otherwise the list of options
    var sum = e.target.closest('.share-menu > summary');
    if (sum && navigator.share) {
      var d = sum.parentNode;
      e.preventDefault();
      navigator.share({ title: document.title, text: d.getAttribute('data-share-text') || '', url: d.getAttribute('data-share-url') }).catch(function () {});
      return;
    }
    // close an open share list when clicking elsewhere
    document.querySelectorAll('.share-menu[open]').forEach(function (m) { if (!m.contains(e.target)) m.removeAttribute('open'); });
  });

  function show(g, thumbs, to) {
    var main = g.querySelector('[data-main]');
    if (main) main.src = thumbs[to].getAttribute('data-src');
    for (var i = 0; i < thumbs.length; i++) thumbs[i].classList.toggle('on', i === to);
    var n = g.querySelector('[data-at]');
    if (n) n.textContent = String(to + 1);
  }

  document.addEventListener('keydown', function (e) {
    var g = document.querySelector('[data-gallery]');
    if (!g || (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft')) return;
    if (e.target.closest('input,textarea,select')) return;
    var b = g.querySelector(e.key === 'ArrowRight' ? '[data-next]' : '[data-prev]');
    if (b) b.click();
  });

  // swipe on the big picture
  var x0 = null;
  document.addEventListener('touchstart', function (e) { if (e.target.closest('.stage')) x0 = e.touches[0].clientX; }, { passive: true });
  document.addEventListener('touchend', function (e) {
    if (x0 === null) return;
    var dx = e.changedTouches[0].clientX - x0;
    x0 = null;
    if (Math.abs(dx) < 40) return;
    var b = document.querySelector(dx < 0 ? '[data-gallery] [data-next]' : '[data-gallery] [data-prev]');
    if (b) b.click();
  }, { passive: true });

  // one submit per form (the order is created once whatever happens)
  document.addEventListener('submit', function (e) {
    var f = e.target;
    if (f.dataset.sent) { e.preventDefault(); return; }
    f.dataset.sent = '1';
    var btn = e.submitter || f.querySelector('button[type=submit]');
    if (btn && !btn.name && !btn.querySelector('svg')) { btn.disabled = true; btn.textContent = 'Please wait…'; }
  });

  // chat: new messages every few seconds while the page is open
  var log = document.querySelector('[data-chat]');
  if (log) {
    var end = document.getElementById('end');
    if (end) end.scrollIntoView();
    var after = parseInt(log.getAttribute('data-after'), 10) || 0;
    var poll = function () {
      if (document.hidden) return;
      fetch(log.getAttribute('data-poll') + '?after=' + after, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
        .then(function (r) { return r.ok ? r.json() : { messages: [] }; })
        .then(function (d) {
          (d.messages || []).forEach(function (m) {
            var empty = log.querySelector('[data-empty]');
            if (empty) empty.remove();
            var div = document.createElement('div');
            div.className = 'msg ' + m.from;
            var span = document.createElement('span');
            span.textContent = m.text;
            var small = document.createElement('small');
            small.textContent = m.time;
            div.appendChild(span); div.appendChild(small);
            log.insertBefore(div, end);
            after = Math.max(after, m.id);
            if (end) end.scrollIntoView();
          });
        }).catch(function () {});
    };
    setInterval(poll, 4000);
  }
})();

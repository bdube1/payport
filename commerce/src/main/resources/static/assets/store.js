// Copy-link buttons, and one submit per form (the order is created once whatever happens).
document.addEventListener('click', function (e) {
  var b = e.target.closest('[data-copy]');
  if (!b) return;
  var text = b.getAttribute('data-copy');
  var done = function () { var t = b.textContent; b.textContent = 'Copied'; setTimeout(function () { b.textContent = t; }, 1500); };
  if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, function () {});
});
document.addEventListener('submit', function (e) {
  var f = e.target;
  if (f.dataset.sent) { e.preventDefault(); return; }
  f.dataset.sent = '1';
  var btn = f.querySelector('button[type=submit]');
  if (btn) { btn.disabled = true; btn.textContent = 'Please wait…'; }
});

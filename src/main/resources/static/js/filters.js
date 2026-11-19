// Submits a filter form as soon as one of its <select data-auto-submit> changes. Kept out of the
// templates because the Content-Security-Policy allows scripts from this site's files only.
(function () {
  'use strict';
  document.querySelectorAll('select[data-auto-submit]').forEach(function (select) {
    select.addEventListener('change', function () {
      select.form.submit();
    });
  });
})();

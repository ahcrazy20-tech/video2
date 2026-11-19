// Hide only known ad containers; do not remove video elements or change the capture script.
(function () {
  'use strict';
  var id = '__kadr_ad_style';
  function install() {
    if (document.getElementById(id)) return;
    var parent = document.head || document.documentElement;
    if (!parent) return;
    var style = document.createElement('style');
    style.id = id;
    style.textContent = 'ins.adsbygoogle[data-ad-client],ins.adsbygoogle[data-ad-slot],'
      + 'iframe[id^="google_ads_iframe_"],div[id^="div-gpt-ad-"][data-google-query-id]'
      + '{display:none!important}';
    parent.appendChild(style);
  }
  if (document.documentElement) install();
  else document.addEventListener('DOMContentLoaded', install, { once: true });
})();

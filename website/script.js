(function () {
  "use strict";

  /* ------------------------------------------------------------
     Mobile navigation toggle
  ------------------------------------------------------------ */
  var header = document.getElementById("site-header");
  var toggle = document.getElementById("nav-toggle");
  var nav = document.getElementById("main-nav");

  if (toggle && header && nav) {
    toggle.addEventListener("click", function () {
      var isOpen = header.classList.toggle("nav-open");
      toggle.setAttribute("aria-expanded", isOpen ? "true" : "false");
    });

    nav.addEventListener("click", function (event) {
      if (event.target.tagName === "A") {
        header.classList.remove("nav-open");
        toggle.setAttribute("aria-expanded", "false");
      }
    });

    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && header.classList.contains("nav-open")) {
        header.classList.remove("nav-open");
        toggle.setAttribute("aria-expanded", "false");
        toggle.focus();
      }
    });
  }

  /* ------------------------------------------------------------
     Latest release version — fetched client-side from the public
     GitHub Releases API. Falls back silently to static text if
     the request fails or is rate-limited, since this is a static
     site with no backend of its own.
  ------------------------------------------------------------ */
  var versionEl = document.getElementById("release-version");

  if (versionEl) {
    fetch("https://api.github.com/repos/DhairyaSood/hybrid-mesh-relay/releases/latest")
      .then(function (response) {
        if (!response.ok) {
          throw new Error("release lookup failed");
        }
        return response.json();
      })
      .then(function (data) {
        if (data && data.tag_name) {
          versionEl.textContent = "version: " + data.tag_name;
        }
      })
      .catch(function () {
        /* Keep the static "version: latest" fallback already in the markup. */
      });
  }
})();

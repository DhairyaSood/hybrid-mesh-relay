export default async function handler(req, res) {
  try {
    if (req.method !== "GET") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    const repo = "DhairyaSood/hybrid-mesh-relay";

    // Get the latest GitHub release
    const releaseResponse = await fetch(
      `https://api.github.com/repos/${repo}/releases/latest`,
      {
        headers: {
          Accept: "application/vnd.github+json",
          "User-Agent": "hybrid-mesh-relay-website",
        },
      }
    );

    if (!releaseResponse.ok) {
      throw new Error(
        `GitHub release lookup failed: ${releaseResponse.status}`
      );
    }

    const release = await releaseResponse.json();

    // Find the APK
    const apk = release.assets?.find(
      (asset) => asset.name === "hybrid-mesh-relay.apk"
    );

    if (!apk) {
      throw new Error("Latest release does not contain hybrid-mesh-relay.apk");
    }

    // Fetch the actual APK binary from GitHub's asset API.
    // GitHub redirects this request to its storage CDN, but the browser
    // never sees that redirect because Vercel proxies the file.
    const apkResponse = await fetch(apk.url, {
      headers: {
        Accept: "application/octet-stream",
        "User-Agent": "hybrid-mesh-relay-website",
      },
    });

    if (!apkResponse.ok) {
      throw new Error(`APK download failed: ${apkResponse.status}`);
    }

    const buffer = Buffer.from(await apkResponse.arrayBuffer());

    res.statusCode = 200;
    res.setHeader("Content-Type", "application/vnd.android.package-archive");
    res.setHeader(
      "Content-Disposition",
      'attachment; filename="hybrid-mesh-relay.apk"'
    );
    res.setHeader("Content-Length", buffer.length);
    res.setHeader("Cache-Control", "public, max-age=300");

    res.end(buffer);
  } catch (error) {
    console.error(error);

    res.status(500).json({
      error: "Unable to download the latest APK",
    });
  }
}
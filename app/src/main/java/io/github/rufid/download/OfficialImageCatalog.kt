package io.github.rufid.download

data class OfficialImage(
    val name: String,
    val family: String,
    val release: String,
    val pageUrl: String,
    val directImageUrl: String?,
    val checksumUrl: String?,
    val note: String,
) {
    val pickerLabel: String
        get() = "$name\n$release"
}

object OfficialImageCatalog {
    val current: List<OfficialImage> = listOf(
        OfficialImage(
            name = "Windows 11",
            family = "Windows",
            release = "Official Microsoft ISO download page",
            pageUrl = "https://www.microsoft.com/en-us/software-download/windows11",
            directImageUrl = null,
            checksumUrl = null,
            note = "Microsoft serves language/session-specific ISO links. Open the official page, download the ISO, then select it in Rufid.",
        ),
        OfficialImage(
            name = "Ubuntu Desktop",
            family = "Linux",
            release = "26.04 LTS amd64",
            pageUrl = "https://releases.ubuntu.com/26.04/",
            directImageUrl = "https://releases.ubuntu.com/26.04/ubuntu-26.04-desktop-amd64.iso",
            checksumUrl = "https://releases.ubuntu.com/26.04/SHA256SUMS",
            note = "Direct official Ubuntu release image.",
        ),
        OfficialImage(
            name = "Fedora Workstation",
            family = "Linux",
            release = "44 x86_64",
            pageUrl = "https://fedoraproject.org/workstation/download/",
            directImageUrl = null,
            checksumUrl = null,
            note = "Use the official Fedora page so mirror selection and checksums stay current.",
        ),
        OfficialImage(
            name = "Debian netinst",
            family = "Linux",
            release = "13.5.0 amd64",
            pageUrl = "https://www.debian.org/download",
            directImageUrl = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/debian-13.5.0-amd64-netinst.iso",
            checksumUrl = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/SHA512SUMS",
            note = "Small official Debian installer image.",
        ),
        OfficialImage(
            name = "Linux Mint Cinnamon",
            family = "Linux",
            release = "22.3 Zena 64-bit",
            pageUrl = "https://www.linuxmint.com/download.php",
            directImageUrl = "https://mirrors.edge.kernel.org/linuxmint/stable/22.3/linuxmint-22.3-cinnamon-64bit.iso",
            checksumUrl = "https://mirrors.edge.kernel.org/linuxmint/stable/22.3/sha256sum.txt",
            note = "Official Linux Mint mirror image.",
        ),
        OfficialImage(
            name = "Arch Linux",
            family = "Linux",
            release = "2026.06.01 x86_64",
            pageUrl = "https://archlinux.org/download/",
            directImageUrl = "https://geo.mirror.pkgbuild.com/iso/2026.06.01/archlinux-2026.06.01-x86_64.iso",
            checksumUrl = "https://geo.mirror.pkgbuild.com/iso/2026.06.01/sha256sums.txt",
            note = "Official Arch monthly install image mirror.",
        ),
        OfficialImage(
            name = "openSUSE Tumbleweed",
            family = "Linux",
            release = "Current DVD x86_64",
            pageUrl = "https://get.opensuse.org/tumbleweed/",
            directImageUrl = "https://download.opensuse.org/tumbleweed/iso/openSUSE-Tumbleweed-DVD-x86_64-Current.iso",
            checksumUrl = "https://download.opensuse.org/tumbleweed/iso/openSUSE-Tumbleweed-DVD-x86_64-Current.iso.sha256",
            note = "Rolling release image from the official openSUSE download service.",
        ),
    )
}

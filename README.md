# yocto-rpi5-wifi-ssh

A Yocto layer that builds a minimal Linux image for the Raspberry Pi 5. The board joins your Wi-Fi on boot and you log in over SSH with a key, no monitor or Ethernet needed.

It is the Yocto counterpart of my `buildroot-rpi5-wifi-ssh` repository: same board, same goal, different build system.

Tested on a Raspberry Pi 5 Rev 1.1 (BCM2712 D0): Poky 5.0.21, kernel 6.6.63, root filesystem around 120 MB, image 75 MB compressed.

## What's inside

```
meta-own/
  conf/layer.conf                          layer definition, compatible with scarthgap
  conf/templates/default/                  local.conf and bblayers.conf used for this build
  recipes-core/images/own-image.bb         the image, injects secrets at do_rootfs
  recipes-core/init-ifupdown/              /etc/network/interfaces for wlan0
  recipes-core/base-files/                 hostname rpi5-hw
  recipes-core/ssh-authorized-keys/        public key allowed to log in as root
secrets.env.example                        variables you need to provide
```

The image is `core-image` plus Dropbear, wpa_supplicant, iw and the static wireless regulatory database. `meta-raspberrypi` adds the kernel, the Device Tree overlays and the Broadcom Wi-Fi firmware.

## Requirements

You need a Linux host supported by Yocto 5.0, for example Ubuntu 24.04, with the [host packages](https://docs.yoctoproject.org/scarthgap/ref-manual/system-requirements.html#ubuntu-and-debian) and the `en_US.UTF-8` locale. With `rm_work` enabled, this build took about 22 GB: 9.6 GB in `tmp`, 7.5 GB of downloads, 3.8 GB of sstate cache.

I built it on a MacBook with Apple Silicon, in an arm64 `ubuntu:24.04` container. Yocto's docs only show x86_64 hosts, but the build went through on arm64 without changes. In a container, check that `unshare -Urn true` works for your build user; if it doesn't, BitBake stops with "User namespaces are not usable" and the container needs `--security-opt seccomp=unconfined`.

Don't run BitBake as root.

## Build

The template in `meta-own/conf/templates/default` assumes this layout:

```
/work/poky               git clone -b scarthgap https://git.yoctoproject.org/poky
/work/meta-raspberrypi   git clone -b scarthgap https://github.com/agherzan/meta-raspberrypi
/project                 this repository
```

Other paths work too, but then fix them in `bblayers.conf` after the first step.

```sh
cd /work
git clone -b scarthgap https://git.yoctoproject.org/poky
git clone -b scarthgap https://github.com/agherzan/meta-raspberrypi

# Secrets live outside the repository
cp /project/secrets.env.example /work/rpi5-secrets.env
chmod 600 /work/rpi5-secrets.env
$EDITOR /work/rpi5-secrets.env

# Create the build directory from the saved template and build
TEMPLATECONF=/project/meta-own/conf/templates/default source poky/oe-init-build-env /work/build
bitbake own-image
```

The first build took about six hours on an M2 Pro with 8 CPUs given to Docker (5614 BitBake tasks). Later builds reuse the sstate cache and only redo what changed.

Put your own public key in `meta-own/recipes-core/ssh-authorized-keys/files/authorized_keys` before building. The one in the repo is mine.

The result is `tmp/deploy/images/raspberrypi5/own-image-raspberrypi5.rootfs.wic.bz2`.

## Secrets

The repository has no passwords and no hashes. `own-image.bb` reads them from the file that `RPI5_SECRETS` in `local.conf` points to:

| Variable | Meaning |
|---|---|
| `ROOT_PASSWORD_HASH` | crypt hash for root, `$6$...` (SHA-512). Generate it with `mkpasswd -m sha-512` or `openssl passwd -6` |
| `WIFI_SSID` | network name |
| `WIFI_PASSWORD` | Wi-Fi passphrase. The recipe derives the WPA2 PSK from it |
| `WIFI_PSK` | a precomputed 64-hex-digit PSK, instead of `WIFI_PASSWORD` |
| `WIFI_COUNTRY` | optional regulatory domain, `UA` by default |

The file is read while `do_rootfs` runs, so the values never enter the BitBake datastore, task signatures or sstate. BitBake only tracks the file's checksum (`do_rootfs[file-checksums]`), and editing the file triggers a new `do_rootfs`. If the file or a variable is missing, the build stops with an error, so it can't produce an image where root has no password.

The secrets do end up inside the image, since the board needs them. That is why images are in `.gitignore`.

## Flash and connect

On macOS:

```sh
diskutil list external
diskutil unmountDisk /dev/diskN
bzcat own-image-raspberrypi5.rootfs.wic.bz2 | sudo dd of=/dev/rdiskN bs=4m status=progress
diskutil eject /dev/diskN
```

On Linux you can use `bmaptool copy`: it reads the `.wic.bmap` file next to the image and writes only the blocks that hold data. Plain `dd` with `of=/dev/sdX` works too. Double-check the device name either way.

The board sends the hostname `rpi5-hw` in its DHCP request, so most routers list it under that name. Then:

```sh
ssh -i ~/.ssh/your_key root@<board-ip>
```

Every reflash generates a new Dropbear host key. If SSH warns that the host identification changed, run `ssh-keygen -R <board-ip>`.

To check what's running: `cat /etc/issue` shows the Poky version. The minimal image has no `/etc/os-release`.

## Notes on this build

### Root login over SSH

Poky starts Dropbear with `-w`, which blocks root logins. The image enables the `allow-root-login` feature to drop that flag. It does not use `debug-tweaks`, which would also allow empty passwords. The default Poky `local.conf` turns `debug-tweaks` on through `EXTRA_IMAGE_FEATURES`. The saved template sets it to an empty string.

### Wi-Fi

The firmware from Raspberry Pi is under the Synaptics license, so `local.conf` sets `LICENSE_FLAGS_ACCEPTED = "synaptics-killswitch"`.

Poky's wpa_supplicant package installs an ifupdown hook that starts the daemon in `pre-up` when the interface has a `wpa-conf` line. The interfaces file only waits for `wlan0` to appear (the firmware loads asynchronously), sends the hostname over DHCP and turns power save off after the link is up. With power save on, `brcmfmac` gave 300 ms pings and packet loss on the Buildroot image.

### Differences from the Buildroot version

`meta-raspberrypi` installs the `bcm2712d0.dtbo` overlay, so D0-stepping boards boot fine. With the Buildroot 2025.02 config the same board hit a kernel panic until I enabled the overlays.

Scarthgap ships wpa_supplicant 2.10. The "port authorized" timeout with `brcmfmac` started with 2.11, so the `feature_disable=0x82000` driver workaround from the Buildroot tree is not needed here.

### A BitBake gotcha

BitBake ends a shell function at the first line that starts with `}`. That rules out a heredoc whose content has a `}` at the start of a line, such as the closing brace of `network={...}` in `wpa_supplicant.conf`. The recipe writes that file with `printf` instead.

## References

- [Yocto Project 5.0 documentation](https://docs.yoctoproject.org/scarthgap/)
- [meta-raspberrypi, scarthgap branch](https://github.com/agherzan/meta-raspberrypi/tree/scarthgap)
- [rootfs-postcommands.bbclass](https://git.yoctoproject.org/poky/tree/meta/classes-recipe/rootfs-postcommands.bbclass?h=scarthgap), where `allow-root-login` and `debug-tweaks` are applied

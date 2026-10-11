SUMMARY = "Raspberry Pi 5 image with Dropbear SSH over Wi-Fi"
LICENSE = "MIT"

inherit core-image

# Dropbear SSH server. Poky starts dropbear with -w (no root login)
# unless allow-root-login is enabled.
IMAGE_FEATURES += "ssh-server-dropbear allow-root-login"

IMAGE_INSTALL += " \
    wpa-supplicant \
    iw \
    wireless-regdb-static \
    ssh-authorized-keys \
"

# Re-run do_rootfs when the secrets file changes (file content is hashed,
# the secrets themselves never enter the BitBake datastore)
do_rootfs[file-checksums] += "${@'${RPI5_SECRETS}:True' if d.getVar('RPI5_SECRETS') else ''}"

ROOTFS_POSTPROCESS_COMMAND += "own_inject_secrets; "

own_inject_secrets () {
	if [ -z "${RPI5_SECRETS}" ] || [ ! -r "${RPI5_SECRETS}" ]; then
		bbfatal "RPI5_SECRETS is not set or not readable, see secrets.env.example"
	fi
	set -a
	. "${RPI5_SECRETS}"
	set +a

	# Root password
	[ -n "$ROOT_PASSWORD_HASH" ] || bbfatal "ROOT_PASSWORD_HASH is not set"
	case "$ROOT_PASSWORD_HASH" in
		'$5$'*|'$6$'*) ;;
		*) bbfatal "ROOT_PASSWORD_HASH must be a SHA-256/SHA-512 crypt hash" ;;
	esac
	if [ -e ${IMAGE_ROOTFS}${sysconfdir}/shadow ]; then
		pwfile=${IMAGE_ROOTFS}${sysconfdir}/shadow
	else
		pwfile=${IMAGE_ROOTFS}${sysconfdir}/passwd
	fi
	sed -i "s|^root:[^:]*:|root:$ROOT_PASSWORD_HASH:|" "$pwfile"

	# Wi-Fi
	[ -n "$WIFI_SSID" ] || bbfatal "WIFI_SSID is not set"
	if [ -z "$WIFI_PSK" ]; then
		[ -n "$WIFI_PASSWORD" ] || bbfatal "WIFI_PASSWORD (or WIFI_PSK) is not set"
		WIFI_PSK=$(python3 -c 'import hashlib, os; print(hashlib.pbkdf2_hmac("sha1", os.environ["WIFI_PASSWORD"].encode(), os.environ["WIFI_SSID"].encode(), 4096, 32).hex())')
	fi
	echo "$WIFI_PSK" | grep -qE '^[0-9a-fA-F]{64}$' || bbfatal "WIFI_PSK must be 64 hex digits"
	ssid_hex=$(python3 -c 'import os; print(os.environ["WIFI_SSID"].encode().hex())')
	country=$WIFI_COUNTRY
	[ -n "$country" ] || country=UA

	# printf instead of a heredoc: BitBake ends a shell function at the first
	# line that starts with "}", so the closing brace of network={...} must
	# not appear at column 0 of the recipe
	printf '%s\n' \
		"# Generated at build time from the secrets file" \
		"ctrl_interface=/var/run/wpa_supplicant" \
		"update_config=1" \
		"country=$country" \
		"" \
		"network={" \
		"    ssid=$ssid_hex" \
		"    psk=$WIFI_PSK" \
		"    key_mgmt=WPA-PSK" \
		"}" \
		> ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant.conf
	chmod 600 ${IMAGE_ROOTFS}${sysconfdir}/wpa_supplicant.conf
}


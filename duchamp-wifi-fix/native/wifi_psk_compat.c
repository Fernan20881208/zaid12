/*
 * SPDX-License-Identifier: MIT
 *
 * Convert quoted WPA2 passphrases in Android's WifiConfigStore.xml to the
 * equivalent 64-hex-digit PMK. Android 17 deliberately skips PSK/SAE
 * Cross-AKM expansion for this representation.
 *
 * The program never prints an SSID, passphrase, or derived PMK.
 */

#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define WIFI_CONFIG_MAX_BYTES (16U * 1024U * 1024U)
#define SHA1_BLOCK_BYTES 64U
#define SHA1_DIGEST_BYTES 20U

typedef struct {
    uint32_t state[5];
    uint64_t byte_count;
    uint8_t buffer[SHA1_BLOCK_BYTES];
    size_t buffer_len;
} sha1_ctx;

static uint32_t rol32(uint32_t value, unsigned int bits) {
    return (value << bits) | (value >> (32U - bits));
}

static void secure_zero(void *ptr, size_t len) {
    volatile uint8_t *p = (volatile uint8_t *)ptr;
    while (len-- != 0U) {
        *p++ = 0;
    }
}

static void sha1_transform(sha1_ctx *ctx, const uint8_t block[SHA1_BLOCK_BYTES]) {
    uint32_t w[80];
    uint32_t a;
    uint32_t b;
    uint32_t c;
    uint32_t d;
    uint32_t e;

    for (size_t i = 0; i < 16U; ++i) {
        const size_t offset = i * 4U;
        w[i] = ((uint32_t)block[offset] << 24U)
                | ((uint32_t)block[offset + 1U] << 16U)
                | ((uint32_t)block[offset + 2U] << 8U)
                | (uint32_t)block[offset + 3U];
    }
    for (size_t i = 16U; i < 80U; ++i) {
        w[i] = rol32(w[i - 3U] ^ w[i - 8U] ^ w[i - 14U] ^ w[i - 16U], 1U);
    }

    a = ctx->state[0];
    b = ctx->state[1];
    c = ctx->state[2];
    d = ctx->state[3];
    e = ctx->state[4];

    for (size_t i = 0; i < 80U; ++i) {
        uint32_t f;
        uint32_t k;
        if (i < 20U) {
            f = (b & c) | ((~b) & d);
            k = UINT32_C(0x5a827999);
        } else if (i < 40U) {
            f = b ^ c ^ d;
            k = UINT32_C(0x6ed9eba1);
        } else if (i < 60U) {
            f = (b & c) | (b & d) | (c & d);
            k = UINT32_C(0x8f1bbcdc);
        } else {
            f = b ^ c ^ d;
            k = UINT32_C(0xca62c1d6);
        }
        const uint32_t temp = rol32(a, 5U) + f + e + k + w[i];
        e = d;
        d = c;
        c = rol32(b, 30U);
        b = a;
        a = temp;
    }

    ctx->state[0] += a;
    ctx->state[1] += b;
    ctx->state[2] += c;
    ctx->state[3] += d;
    ctx->state[4] += e;
    secure_zero(w, sizeof(w));
}

static void sha1_init(sha1_ctx *ctx) {
    ctx->state[0] = UINT32_C(0x67452301);
    ctx->state[1] = UINT32_C(0xefcdab89);
    ctx->state[2] = UINT32_C(0x98badcfe);
    ctx->state[3] = UINT32_C(0x10325476);
    ctx->state[4] = UINT32_C(0xc3d2e1f0);
    ctx->byte_count = 0U;
    ctx->buffer_len = 0U;
    memset(ctx->buffer, 0, sizeof(ctx->buffer));
}

static void sha1_update(sha1_ctx *ctx, const uint8_t *data, size_t len) {
    ctx->byte_count += (uint64_t)len;
    while (len != 0U) {
        size_t take = SHA1_BLOCK_BYTES - ctx->buffer_len;
        if (take > len) {
            take = len;
        }
        memcpy(ctx->buffer + ctx->buffer_len, data, take);
        ctx->buffer_len += take;
        data += take;
        len -= take;
        if (ctx->buffer_len == SHA1_BLOCK_BYTES) {
            sha1_transform(ctx, ctx->buffer);
            ctx->buffer_len = 0U;
        }
    }
}

static void sha1_final(sha1_ctx *ctx, uint8_t digest[SHA1_DIGEST_BYTES]) {
    const uint64_t bit_count = ctx->byte_count * UINT64_C(8);
    uint8_t padding[SHA1_BLOCK_BYTES] = {0x80U};
    uint8_t length_be[8];
    const size_t pad_len = ctx->buffer_len < 56U
            ? 56U - ctx->buffer_len
            : 120U - ctx->buffer_len;

    for (size_t i = 0; i < sizeof(length_be); ++i) {
        length_be[sizeof(length_be) - 1U - i] = (uint8_t)(bit_count >> (i * 8U));
    }
    sha1_update(ctx, padding, pad_len);
    sha1_update(ctx, length_be, sizeof(length_be));

    for (size_t i = 0; i < 5U; ++i) {
        digest[i * 4U] = (uint8_t)(ctx->state[i] >> 24U);
        digest[i * 4U + 1U] = (uint8_t)(ctx->state[i] >> 16U);
        digest[i * 4U + 2U] = (uint8_t)(ctx->state[i] >> 8U);
        digest[i * 4U + 3U] = (uint8_t)ctx->state[i];
    }
    secure_zero(ctx, sizeof(*ctx));
    secure_zero(padding, sizeof(padding));
    secure_zero(length_be, sizeof(length_be));
}

static void hmac_sha1_2(const uint8_t *key, size_t key_len,
        const uint8_t *part1, size_t part1_len,
        const uint8_t *part2, size_t part2_len,
        uint8_t digest[SHA1_DIGEST_BYTES]) {
    uint8_t key_block[SHA1_BLOCK_BYTES] = {0};
    uint8_t inner_pad[SHA1_BLOCK_BYTES];
    uint8_t outer_pad[SHA1_BLOCK_BYTES];
    uint8_t inner_digest[SHA1_DIGEST_BYTES];
    sha1_ctx ctx;

    if (key_len > SHA1_BLOCK_BYTES) {
        sha1_init(&ctx);
        sha1_update(&ctx, key, key_len);
        sha1_final(&ctx, key_block);
    } else if (key_len != 0U) {
        memcpy(key_block, key, key_len);
    }

    for (size_t i = 0; i < SHA1_BLOCK_BYTES; ++i) {
        inner_pad[i] = key_block[i] ^ 0x36U;
        outer_pad[i] = key_block[i] ^ 0x5cU;
    }

    sha1_init(&ctx);
    sha1_update(&ctx, inner_pad, sizeof(inner_pad));
    sha1_update(&ctx, part1, part1_len);
    if (part2 != NULL && part2_len != 0U) {
        sha1_update(&ctx, part2, part2_len);
    }
    sha1_final(&ctx, inner_digest);

    sha1_init(&ctx);
    sha1_update(&ctx, outer_pad, sizeof(outer_pad));
    sha1_update(&ctx, inner_digest, sizeof(inner_digest));
    sha1_final(&ctx, digest);

    secure_zero(key_block, sizeof(key_block));
    secure_zero(inner_pad, sizeof(inner_pad));
    secure_zero(outer_pad, sizeof(outer_pad));
    secure_zero(inner_digest, sizeof(inner_digest));
}

static void pbkdf2_sha1(const uint8_t *passphrase, size_t passphrase_len,
        const uint8_t *ssid, size_t ssid_len, uint8_t pmk[32]) {
    uint8_t u[SHA1_DIGEST_BYTES];
    uint8_t t[SHA1_DIGEST_BYTES];
    size_t written = 0U;

    for (uint32_t block_index = 1U; written < 32U; ++block_index) {
        uint8_t block_be[4] = {
            (uint8_t)(block_index >> 24U),
            (uint8_t)(block_index >> 16U),
            (uint8_t)(block_index >> 8U),
            (uint8_t)block_index
        };
        hmac_sha1_2(passphrase, passphrase_len, ssid, ssid_len,
                block_be, sizeof(block_be), u);
        memcpy(t, u, sizeof(t));
        for (unsigned int iteration = 1U; iteration < 4096U; ++iteration) {
            hmac_sha1_2(passphrase, passphrase_len, u, sizeof(u), NULL, 0U, u);
            for (size_t i = 0; i < sizeof(t); ++i) {
                t[i] ^= u[i];
            }
        }
        size_t take = sizeof(t);
        if (take > 32U - written) {
            take = 32U - written;
        }
        memcpy(pmk + written, t, take);
        written += take;
        secure_zero(block_be, sizeof(block_be));
    }
    secure_zero(u, sizeof(u));
    secure_zero(t, sizeof(t));
}

static const char *find_bytes(const char *start, const char *end, const char *needle) {
    const size_t needle_len = strlen(needle);
    if (needle_len == 0U || start > end || (size_t)(end - start) < needle_len) {
        return NULL;
    }
    const char first = needle[0];
    for (const char *cursor = start; cursor + needle_len <= end; ++cursor) {
        if (*cursor == first && memcmp(cursor, needle, needle_len) == 0) {
            return cursor;
        }
    }
    return NULL;
}

static int append_bytes(char **buffer, size_t *length, size_t *capacity,
        const char *data, size_t data_len) {
    if (data_len > SIZE_MAX - *length - 1U) {
        return -1;
    }
    const size_t required = *length + data_len + 1U;
    if (required > *capacity) {
        size_t new_capacity = *capacity == 0U ? 4096U : *capacity;
        while (new_capacity < required) {
            if (new_capacity > SIZE_MAX / 2U) {
                new_capacity = required;
                break;
            }
            new_capacity *= 2U;
        }
        char *resized = realloc(*buffer, new_capacity);
        if (resized == NULL) {
            return -1;
        }
        *buffer = resized;
        *capacity = new_capacity;
    }
    memcpy(*buffer + *length, data, data_len);
    *length += data_len;
    (*buffer)[*length] = '\0';
    return 0;
}

static char *replace_range(const char *source, size_t source_len,
        size_t begin, size_t end, const char *replacement, size_t replacement_len,
        size_t *result_len) {
    if (begin > end || end > source_len
            || replacement_len > SIZE_MAX - (source_len - (end - begin)) - 1U) {
        return NULL;
    }
    *result_len = source_len - (end - begin) + replacement_len;
    char *result = malloc(*result_len + 1U);
    if (result == NULL) {
        return NULL;
    }
    memcpy(result, source, begin);
    memcpy(result + begin, replacement, replacement_len);
    memcpy(result + begin + replacement_len, source + end, source_len - end);
    result[*result_len] = '\0';
    return result;
}

static int replace_first_literal(char **source, size_t *source_len,
        const char *old_text, const char *new_text) {
    const char *match = find_bytes(*source, *source + *source_len, old_text);
    if (match == NULL) {
        return 0;
    }
    size_t new_len = 0U;
    const size_t offset = (size_t)(match - *source);
    char *updated = replace_range(*source, *source_len, offset,
            offset + strlen(old_text), new_text, strlen(new_text), &new_len);
    if (updated == NULL) {
        return -1;
    }
    free(*source);
    *source = updated;
    *source_len = new_len;
    return 1;
}

static int is_auto_upgrade_sae_params(const char *block, size_t block_len) {
    static const char sae_security_type[] = "name=\"SecurityType\" value=\"4\"";
    static const char added_by_auto_upgrade[] =
            "name=\"IsAddedByAutoUpgrade\" value=\"true\"";
    return find_bytes(block, block + block_len, sae_security_type) != NULL
            && find_bytes(block, block + block_len, added_by_auto_upgrade) != NULL;
}

static int contains_auto_upgrade_sae_params(const char *source, size_t source_len) {
    static const char params_open[] = "<SecurityParams>";
    static const char params_close[] = "</SecurityParams>";
    const char *cursor = source;
    const char *end = source + source_len;

    while (cursor < end) {
        const char *start = find_bytes(cursor, end, params_open);
        if (start == NULL) {
            return 0;
        }
        const char *finish = find_bytes(start, end, params_close);
        if (finish == NULL) {
            return -1;
        }
        finish += strlen(params_close);
        if (is_auto_upgrade_sae_params(start, (size_t)(finish - start))) {
            return 1;
        }
        cursor = finish;
    }
    return 0;
}

static int remove_auto_upgrade_sae_params(char **source, size_t *source_len) {
    static const char params_open[] = "<SecurityParams>";
    static const char params_close[] = "</SecurityParams>";
    size_t search_offset = 0U;
    int removed = 0;

    while (search_offset < *source_len) {
        const char *start = find_bytes(*source + search_offset,
                *source + *source_len, params_open);
        if (start == NULL) {
            break;
        }
        const char *finish = find_bytes(start, *source + *source_len, params_close);
        if (finish == NULL) {
            return -1;
        }
        finish += strlen(params_close);
        const size_t begin = (size_t)(start - *source);
        const size_t end = (size_t)(finish - *source);
        if (!is_auto_upgrade_sae_params(start, end - begin)) {
            search_offset = end;
            continue;
        }

        size_t updated_len = 0U;
        char *updated = replace_range(*source, *source_len,
                begin, end, "", 0U, &updated_len);
        if (updated == NULL) {
            return -1;
        }
        free(*source);
        *source = updated;
        *source_len = updated_len;
        search_offset = begin;
        ++removed;
    }
    return removed;
}

static int is_raw_pmk(const char *value, size_t value_len) {
    if (value_len != 64U) {
        return 0;
    }
    for (size_t i = 0U; i < value_len; ++i) {
        const unsigned char ch = (unsigned char)value[i];
        if (!((ch >= '0' && ch <= '9')
                || (ch >= 'a' && ch <= 'f')
                || (ch >= 'A' && ch <= 'F'))) {
            return 0;
        }
    }
    return 1;
}

static int append_utf8(char *output, size_t output_capacity, size_t *output_len,
        uint32_t codepoint) {
    uint8_t encoded[4];
    size_t encoded_len;
    if (codepoint <= UINT32_C(0x7f)) {
        encoded[0] = (uint8_t)codepoint;
        encoded_len = 1U;
    } else if (codepoint <= UINT32_C(0x7ff)) {
        encoded[0] = (uint8_t)(0xc0U | (codepoint >> 6U));
        encoded[1] = (uint8_t)(0x80U | (codepoint & 0x3fU));
        encoded_len = 2U;
    } else if (codepoint <= UINT32_C(0xffff)
            && !(codepoint >= UINT32_C(0xd800) && codepoint <= UINT32_C(0xdfff))) {
        encoded[0] = (uint8_t)(0xe0U | (codepoint >> 12U));
        encoded[1] = (uint8_t)(0x80U | ((codepoint >> 6U) & 0x3fU));
        encoded[2] = (uint8_t)(0x80U | (codepoint & 0x3fU));
        encoded_len = 3U;
    } else if (codepoint <= UINT32_C(0x10ffff)) {
        encoded[0] = (uint8_t)(0xf0U | (codepoint >> 18U));
        encoded[1] = (uint8_t)(0x80U | ((codepoint >> 12U) & 0x3fU));
        encoded[2] = (uint8_t)(0x80U | ((codepoint >> 6U) & 0x3fU));
        encoded[3] = (uint8_t)(0x80U | (codepoint & 0x3fU));
        encoded_len = 4U;
    } else {
        return -1;
    }
    if (encoded_len > output_capacity - *output_len) {
        return -1;
    }
    memcpy(output + *output_len, encoded, encoded_len);
    *output_len += encoded_len;
    return 0;
}

static char *xml_decode(const char *input, size_t input_len, size_t *output_len) {
    char *output = malloc(input_len + 1U);
    if (output == NULL) {
        return NULL;
    }
    *output_len = 0U;
    for (size_t i = 0U; i < input_len;) {
        if (input[i] != '&') {
            output[(*output_len)++] = input[i++];
            continue;
        }
        const char *semicolon = memchr(input + i, ';', input_len - i);
        if (semicolon == NULL) {
            free(output);
            return NULL;
        }
        const size_t entity_len = (size_t)(semicolon - (input + i)) + 1U;
        char decoded = '\0';
        if (entity_len == 6U && memcmp(input + i, "&quot;", 6U) == 0) {
            decoded = '"';
        } else if (entity_len == 6U && memcmp(input + i, "&apos;", 6U) == 0) {
            decoded = '\'';
        } else if (entity_len == 5U && memcmp(input + i, "&amp;", 5U) == 0) {
            decoded = '&';
        } else if (entity_len == 4U && memcmp(input + i, "&lt;", 4U) == 0) {
            decoded = '<';
        } else if (entity_len == 4U && memcmp(input + i, "&gt;", 4U) == 0) {
            decoded = '>';
        }
        if (decoded != '\0') {
            output[(*output_len)++] = decoded;
            i += entity_len;
            continue;
        }
        if (entity_len >= 4U && input[i + 1U] == '#') {
            size_t digit_index = i + 2U;
            int base = 10;
            if (digit_index < i + entity_len - 1U
                    && (input[digit_index] == 'x' || input[digit_index] == 'X')) {
                base = 16;
                ++digit_index;
            }
            uint32_t codepoint = 0U;
            if (digit_index >= i + entity_len - 1U) {
                free(output);
                return NULL;
            }
            for (; digit_index < i + entity_len - 1U; ++digit_index) {
                const unsigned char ch = (unsigned char)input[digit_index];
                unsigned int value;
                if (ch >= '0' && ch <= '9') {
                    value = (unsigned int)(ch - '0');
                } else if (base == 16 && ch >= 'a' && ch <= 'f') {
                    value = (unsigned int)(ch - 'a') + 10U;
                } else if (base == 16 && ch >= 'A' && ch <= 'F') {
                    value = (unsigned int)(ch - 'A') + 10U;
                } else {
                    free(output);
                    return NULL;
                }
                if (value >= (unsigned int)base
                        || codepoint > (UINT32_MAX - value) / (uint32_t)base) {
                    free(output);
                    return NULL;
                }
                codepoint = codepoint * (uint32_t)base + value;
            }
            if (append_utf8(output, input_len, output_len, codepoint) != 0) {
                free(output);
                return NULL;
            }
            i += entity_len;
            continue;
        }
        free(output);
        return NULL;
    }
    output[*output_len] = '\0';
    return output;
}

static int extract_string_element(const char *block, size_t block_len, const char *name,
        const char **value_start, const char **value_end) {
    char opening[96];
    const int count = snprintf(opening, sizeof(opening), "<string name=\"%s\">", name);
    if (count <= 0 || (size_t)count >= sizeof(opening)) {
        return -1;
    }
    const char *start = find_bytes(block, block + block_len, opening);
    if (start == NULL) {
        return 0;
    }
    start += (size_t)count;
    const char *end = find_bytes(start, block + block_len, "</string>");
    if (end == NULL) {
        return -1;
    }
    *value_start = start;
    *value_end = end;
    return 1;
}

static int transform_network(const char *network, size_t network_len,
        char **transformed, size_t *transformed_len) {
    static const char wrong_password[] =
            "<string name=\"DisableReason\">"
            "NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD</string>";
    static const char psk_security_type[] = "name=\"SecurityType\" value=\"2\"";
    static const char status_disabled[] = "<int name=\"Status\" value=\"1\" />";
    static const char status_enabled[] = "<int name=\"Status\" value=\"2\" />";
    static const char selection_disabled[] =
            "<string name=\"SelectionStatus\">"
            "NETWORK_SELECTION_PERMANENTLY_DISABLED</string>";
    static const char selection_enabled[] =
            "<string name=\"SelectionStatus\">NETWORK_SELECTION_ENABLED</string>";
    static const char disable_reason_enabled[] =
            "<string name=\"DisableReason\">NETWORK_SELECTION_ENABLE</string>";
    const char *ssid_start = NULL;
    const char *ssid_end = NULL;
    const char *psk_start = NULL;
    const char *psk_end = NULL;
    char *ssid = NULL;
    char *passphrase = NULL;
    size_t ssid_len = 0U;
    size_t ssid_alloc_len = 0U;
    size_t passphrase_len = 0U;
    size_t passphrase_alloc_len = 0U;
    uint8_t pmk[32];
    char pmk_hex[65];
    int auto_upgrade_sae = 0;
    int changed = 0;
    int result = 0;

    *transformed = NULL;
    *transformed_len = 0U;
    memset(pmk, 0, sizeof(pmk));
    memset(pmk_hex, 0, sizeof(pmk_hex));

    auto_upgrade_sae = contains_auto_upgrade_sae_params(network, network_len);
    if (auto_upgrade_sae < 0) {
        return -1;
    }
    if (find_bytes(network, network + network_len, psk_security_type) == NULL
            || (find_bytes(network, network + network_len, wrong_password) == NULL
                && auto_upgrade_sae == 0)) {
        return 0;
    }
    if (extract_string_element(network, network_len, "SSID", &ssid_start, &ssid_end) != 1
            || extract_string_element(network, network_len, "PreSharedKey",
                    &psk_start, &psk_end) != 1) {
        return 0;
    }

    ssid = xml_decode(ssid_start, (size_t)(ssid_end - ssid_start), &ssid_len);
    passphrase = xml_decode(psk_start, (size_t)(psk_end - psk_start), &passphrase_len);
    if (ssid == NULL || passphrase == NULL) {
        goto cleanup;
    }
    ssid_alloc_len = ssid_len + 1U;
    passphrase_alloc_len = passphrase_len + 1U;
    if (ssid_len < 2U || ssid[0] != '"' || ssid[ssid_len - 1U] != '"') {
        goto cleanup;
    }

    memmove(ssid, ssid + 1U, ssid_len - 2U);
    ssid_len -= 2U;
    ssid[ssid_len] = '\0';
    if (ssid_len == 0U || ssid_len > 32U) {
        goto cleanup;
    }

    if (passphrase_len >= 2U
            && passphrase[0] == '"' && passphrase[passphrase_len - 1U] == '"') {
        memmove(passphrase, passphrase + 1U, passphrase_len - 2U);
        passphrase_len -= 2U;
        passphrase[passphrase_len] = '\0';
        if (passphrase_len < 8U || passphrase_len > 63U) {
            goto cleanup;
        }

        pbkdf2_sha1((const uint8_t *)passphrase, passphrase_len,
                (const uint8_t *)ssid, ssid_len, pmk);
        static const char hex_digits[] = "0123456789abcdef";
        for (size_t i = 0U; i < sizeof(pmk); ++i) {
            pmk_hex[i * 2U] = hex_digits[pmk[i] >> 4U];
            pmk_hex[i * 2U + 1U] = hex_digits[pmk[i] & 0x0fU];
        }
        pmk_hex[64] = '\0';

        const size_t psk_offset = (size_t)(psk_start - network);
        const size_t psk_end_offset = (size_t)(psk_end - network);
        *transformed = replace_range(network, network_len,
                psk_offset, psk_end_offset, pmk_hex, 64U, transformed_len);
        if (*transformed == NULL) {
            result = -1;
            goto cleanup;
        }
        changed = 1;
    } else if (is_raw_pmk(passphrase, passphrase_len)) {
        *transformed = malloc(network_len + 1U);
        if (*transformed == NULL) {
            result = -1;
            goto cleanup;
        }
        memcpy(*transformed, network, network_len);
        (*transformed)[network_len] = '\0';
        *transformed_len = network_len;
    } else {
        goto cleanup;
    }

    const int removed_sae = remove_auto_upgrade_sae_params(transformed, transformed_len);
    const int changed_status = replace_first_literal(transformed, transformed_len,
            status_disabled, status_enabled);
    const int changed_selection = replace_first_literal(transformed, transformed_len,
            selection_disabled, selection_enabled);
    const int changed_reason = replace_first_literal(transformed, transformed_len,
            wrong_password, disable_reason_enabled);
    if (removed_sae < 0 || changed_status < 0
            || changed_selection < 0 || changed_reason < 0) {
        free(*transformed);
        *transformed = NULL;
        *transformed_len = 0U;
        result = -1;
        goto cleanup;
    }
    if (removed_sae > 0 || changed_status > 0
            || changed_selection > 0 || changed_reason > 0) {
        changed = 1;
    }
    result = changed ? 1 : 0;
    if (result == 0) {
        free(*transformed);
        *transformed = NULL;
        *transformed_len = 0U;
    }

cleanup:
    if (ssid != NULL) {
        secure_zero(ssid, ssid_alloc_len);
        free(ssid);
    }
    if (passphrase != NULL) {
        secure_zero(passphrase, passphrase_alloc_len);
        free(passphrase);
    }
    secure_zero(pmk, sizeof(pmk));
    secure_zero(pmk_hex, sizeof(pmk_hex));
    return result;
}

static int transform_document(const char *input, size_t input_len,
        char **output, size_t *output_len, unsigned int *patched_count) {
    static const char network_open[] = "<Network>";
    static const char network_close[] = "</Network>";
    const char *cursor = input;
    const char *end = input + input_len;
    size_t capacity = 0U;

    *output = NULL;
    *output_len = 0U;
    *patched_count = 0U;
    if (find_bytes(input, end, "<WifiConfigStoreData") == NULL) {
        return -1;
    }

    while (cursor < end) {
        const char *network_start = find_bytes(cursor, end, network_open);
        if (network_start == NULL) {
            if (append_bytes(output, output_len, &capacity,
                        cursor, (size_t)(end - cursor)) != 0) {
                return -1;
            }
            break;
        }
        if (append_bytes(output, output_len, &capacity,
                    cursor, (size_t)(network_start - cursor)) != 0) {
            return -1;
        }
        const char *network_end = find_bytes(network_start, end, network_close);
        if (network_end == NULL) {
            return -1;
        }
        network_end += strlen(network_close);
        char *transformed = NULL;
        size_t transformed_len = 0U;
        const int status = transform_network(network_start,
                (size_t)(network_end - network_start), &transformed, &transformed_len);
        if (status < 0) {
            free(transformed);
            return -1;
        }
        if (status == 1) {
            if (append_bytes(output, output_len, &capacity,
                        transformed, transformed_len) != 0) {
                free(transformed);
                return -1;
            }
            ++(*patched_count);
            free(transformed);
        } else if (append_bytes(output, output_len, &capacity,
                    network_start, (size_t)(network_end - network_start)) != 0) {
            return -1;
        }
        cursor = network_end;
    }
    return 0;
}

static int read_file(const char *path, char **data, size_t *length) {
    struct stat st;
    FILE *file = NULL;
    *data = NULL;
    *length = 0U;
    if (stat(path, &st) != 0 || st.st_size < 0
            || (uint64_t)st.st_size > WIFI_CONFIG_MAX_BYTES) {
        return -1;
    }
    file = fopen(path, "rb");
    if (file == NULL) {
        return -1;
    }
    *length = (size_t)st.st_size;
    *data = malloc(*length + 1U);
    if (*data == NULL) {
        fclose(file);
        return -1;
    }
    if (*length != 0U && fread(*data, 1U, *length, file) != *length) {
        free(*data);
        *data = NULL;
        fclose(file);
        return -1;
    }
    (*data)[*length] = '\0';
    if (fclose(file) != 0) {
        free(*data);
        *data = NULL;
        return -1;
    }
    return 0;
}

static int write_file(const char *path, const char *data, size_t length) {
    FILE *file = fopen(path, "wb");
    int failed = 0;
    if (file == NULL) {
        return -1;
    }
    if (length != 0U && fwrite(data, 1U, length, file) != length) {
        failed = 1;
    }
    if (fflush(file) != 0) {
        failed = 1;
    }
    if (fsync(fileno(file)) != 0) {
        failed = 1;
    }
    if (fclose(file) != 0) {
        failed = 1;
    }
    return failed ? -1 : 0;
}

static int self_test(void) {
    static const uint8_t passphrase[] = "password";
    static const uint8_t ssid[] = "IEEE";
    static const uint8_t expected[32] = {
        0xf4U, 0x2cU, 0x6fU, 0xc5U, 0x2dU, 0xf0U, 0xebU, 0xefU,
        0x9eU, 0xbbU, 0x4bU, 0x90U, 0xb3U, 0x8aU, 0x5fU, 0x90U,
        0x2eU, 0x83U, 0xfeU, 0x1bU, 0x13U, 0x5aU, 0x70U, 0xe2U,
        0x3aU, 0xedU, 0x76U, 0x2eU, 0x97U, 0x10U, 0xa1U, 0x2eU
    };
    uint8_t actual[32];
    pbkdf2_sha1(passphrase, sizeof(passphrase) - 1U,
            ssid, sizeof(ssid) - 1U, actual);
    const int ok = memcmp(actual, expected, sizeof(expected)) == 0;
    secure_zero(actual, sizeof(actual));
    if (!ok) {
        fputs("self-test failed\n", stderr);
        return 1;
    }
    puts("self-test ok");
    return 0;
}

int main(int argc, char **argv) {
    char *input = NULL;
    char *output = NULL;
    size_t input_len = 0U;
    size_t output_len = 0U;
    unsigned int patched_count = 0U;
    int exit_code = 0;

    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        return self_test();
    }
    if (argc != 3 || strcmp(argv[1], argv[2]) == 0) {
        fprintf(stderr, "usage: %s INPUT_XML OUTPUT_XML\n", argv[0]);
        return 2;
    }
    if (read_file(argv[1], &input, &input_len) != 0) {
        fprintf(stderr, "cannot read input: %s\n", strerror(errno));
        return 3;
    }
    if (transform_document(input, input_len,
                &output, &output_len, &patched_count) != 0) {
        fputs("invalid or unsupported WifiConfigStore XML\n", stderr);
        exit_code = 4;
        goto cleanup;
    }
    if (write_file(argv[2], output, output_len) != 0) {
        fprintf(stderr, "cannot write output: %s\n", strerror(errno));
        exit_code = 5;
        goto cleanup;
    }
    printf("patched=%u\n", patched_count);

cleanup:
    if (input != NULL) {
        secure_zero(input, input_len);
        free(input);
    }
    if (output != NULL) {
        secure_zero(output, output_len);
        free(output);
    }
    return exit_code;
}

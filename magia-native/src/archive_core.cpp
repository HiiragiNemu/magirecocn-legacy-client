#include "archive_core.h"

#include <archive.h>
#include <archive_entry.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace cnzip {
namespace {

constexpr std::size_t kMaxPathBytes = 4095;
constexpr std::size_t kMaxNameBytes = 255;
constexpr std::size_t kMaxEntries = 1'000'000;
constexpr std::int64_t kProgressStep = 4LL * 1024LL * 1024LL;

struct ArchiveDeleter {
    void operator()(archive* value) const {
        if (value != nullptr) archive_read_free(value);
    }
};
using ArchivePtr = std::unique_ptr<archive, ArchiveDeleter>;

class Fd {
public:
    Fd() = default;
    explicit Fd(int value) : value_(value) {}
    ~Fd() { reset(); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    Fd(Fd&& other) noexcept : value_(other.release()) {}
    Fd& operator=(Fd&& other) noexcept {
        if (this != &other) reset(other.release());
        return *this;
    }
    int get() const { return value_; }
    explicit operator bool() const { return value_ >= 0; }
    int release() {
        const int out = value_;
        value_ = -1;
        return out;
    }
    void reset(int value = -1) {
        if (value_ >= 0) {
            while (close(value_) != 0 && errno == EINTR) {}
        }
        value_ = value;
    }
private:
    int value_ = -1;
};

void set_error(std::string* error, const std::string& message) {
    if (error != nullptr) *error = message;
}

std::string archive_error(archive* value, const char* prefix) {
    const char* detail = value == nullptr ? nullptr : archive_error_string(value);
    return std::string(prefix) + (detail == nullptr ? "" : std::string(": ") + detail);
}

bool checked_add(std::int64_t a, std::int64_t b, std::int64_t* out) {
    if (a < 0 || b < 0 || a > std::numeric_limits<std::int64_t>::max() - b) return false;
    *out = a + b;
    return true;
}

bool valid_component(const std::string& component) {
    if (component.empty() || component == "." || component == ".." ||
        component.size() > kMaxNameBytes) return false;
    for (unsigned char ch : component) {
        if (ch < 0x20 || ch == 0x7f || ch == '\\') return false;
    }
    return true;
}

bool normalize_path(const char* raw, bool directory, std::string* normalized,
                    std::vector<std::string>* components, std::string* error) {
    if (raw == nullptr || raw[0] == '\0') {
        set_error(error, "archive entry has an empty pathname");
        return false;
    }
    std::string path(raw);
    if (path.size() > kMaxPathBytes || path.front() == '/' || path.front() == '\\') {
        set_error(error, "archive entry pathname is absolute or too long: " + path);
        return false;
    }
    if (path.size() >= 2 &&
        ((path[0] >= 'A' && path[0] <= 'Z') || (path[0] >= 'a' && path[0] <= 'z')) &&
        path[1] == ':') {
        set_error(error, "archive entry uses a drive-prefixed pathname: " + path);
        return false;
    }
    while (directory && !path.empty() && path.back() == '/') path.pop_back();
    if (path.empty()) {
        set_error(error, "archive entry resolves to an empty pathname");
        return false;
    }

    components->clear();
    std::size_t start = 0;
    while (start <= path.size()) {
        const std::size_t slash = path.find('/', start);
        const std::size_t end = slash == std::string::npos ? path.size() : slash;
        std::string part = path.substr(start, end - start);
        if (!valid_component(part)) {
            set_error(error, "archive entry contains an unsafe pathname component: " + path);
            return false;
        }
        components->push_back(std::move(part));
        if (slash == std::string::npos) break;
        start = slash + 1;
        if (start == path.size()) {
            set_error(error, "regular archive entry has a trailing slash: " + path);
            return false;
        }
    }

    normalized->clear();
    for (std::size_t i = 0; i < components->size(); ++i) {
        if (i != 0) normalized->push_back('/');
        normalized->append((*components)[i]);
    }
    return true;
}



bool pread_all(int fd, void* data, std::size_t length, std::int64_t offset,
               std::string* error) {
    auto* out = static_cast<unsigned char*>(data);
    std::size_t done = 0;
    while (done < length) {
        const ssize_t got = pread(fd, out + done, length - done,
                                  static_cast<off_t>(offset + static_cast<std::int64_t>(done)));
        if (got > 0) {
            done += static_cast<std::size_t>(got);
            continue;
        }
        if (got < 0 && errno == EINTR) continue;
        set_error(error, got == 0 ? "unexpected EOF while reading ZIP container"
                                  : "cannot read ZIP container: " + std::string(std::strerror(errno)));
        return false;
    }
    return true;
}

std::uint16_t le16(const unsigned char* p) {
    return static_cast<std::uint16_t>(p[0]) |
           (static_cast<std::uint16_t>(p[1]) << 8);
}

std::uint32_t le32(const unsigned char* p) {
    return static_cast<std::uint32_t>(p[0]) |
           (static_cast<std::uint32_t>(p[1]) << 8) |
           (static_cast<std::uint32_t>(p[2]) << 16) |
           (static_cast<std::uint32_t>(p[3]) << 24);
}

std::uint64_t le64(const unsigned char* p) {
    return static_cast<std::uint64_t>(le32(p)) |
           (static_cast<std::uint64_t>(le32(p + 4)) << 32);
}

bool validate_zip_container(const char* path, std::string* error) {
    Fd fd(open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW));
    if (!fd) {
        set_error(error, "cannot open ZIP container: " + std::string(std::strerror(errno)));
        return false;
    }
    struct stat st {};
    if (fstat(fd.get(), &st) != 0 || st.st_size < 22) {
        set_error(error, "ZIP container is too small or cannot be stat'ed");
        return false;
    }
    const std::int64_t file_size = static_cast<std::int64_t>(st.st_size);
    constexpr std::int64_t kTailMax = 22 + 65535;
    const std::int64_t tail_size = std::min(file_size, kTailMax);
    std::vector<unsigned char> tail(static_cast<std::size_t>(tail_size));
    const std::int64_t tail_offset = file_size - tail_size;
    if (!pread_all(fd.get(), tail.data(), tail.size(), tail_offset, error)) return false;

    std::int64_t eocd_in_tail = -1;
    for (std::int64_t i = tail_size - 22; i >= 0; --i) {
        const unsigned char* p = tail.data() + i;
        if (le32(p) != 0x06054b50U) continue;
        const std::uint16_t comment = le16(p + 20);
        if (i + 22 + comment == tail_size) {
            eocd_in_tail = i;
            break;
        }
    }
    if (eocd_in_tail < 0) {
        set_error(error, "ZIP end-of-central-directory record is missing or truncated");
        return false;
    }

    const unsigned char* eocd = tail.data() + eocd_in_tail;
    const std::int64_t eocd_offset = tail_offset + eocd_in_tail;
    std::uint64_t entries_on_disk = le16(eocd + 8);
    std::uint64_t total_entries = le16(eocd + 10);
    std::uint64_t central_size = le32(eocd + 12);
    std::uint64_t central_offset = le32(eocd + 16);
    const std::uint16_t disk_number = le16(eocd + 4);
    const std::uint16_t central_disk = le16(eocd + 6);

    const bool zip64 = entries_on_disk == 0xffffU || total_entries == 0xffffU ||
                       central_size == 0xffffffffU || central_offset == 0xffffffffU;
    std::int64_t central_end_limit = eocd_offset;
    if (zip64) {
        if (eocd_offset < 20) {
            set_error(error, "ZIP64 locator is missing");
            return false;
        }
        unsigned char locator[20];
        if (!pread_all(fd.get(), locator, sizeof(locator), eocd_offset - 20, error)) return false;
        if (le32(locator) != 0x07064b50U || le32(locator + 4) != 0 || le32(locator + 16) != 1) {
            set_error(error, "ZIP64 locator is invalid or multi-disk ZIP is unsupported");
            return false;
        }
        const std::uint64_t zip64_offset_u = le64(locator + 8);
        if (zip64_offset_u > static_cast<std::uint64_t>(file_size - 56)) {
            set_error(error, "ZIP64 end record points outside the file");
            return false;
        }
        const std::int64_t zip64_offset = static_cast<std::int64_t>(zip64_offset_u);
        unsigned char record[56];
        if (!pread_all(fd.get(), record, sizeof(record), zip64_offset, error)) return false;
        if (le32(record) != 0x06064b50U || le64(record + 4) < 44 ||
            le32(record + 16) != 0 || le32(record + 20) != 0) {
            set_error(error, "ZIP64 end record is invalid or multi-disk ZIP is unsupported");
            return false;
        }
        entries_on_disk = le64(record + 24);
        total_entries = le64(record + 32);
        central_size = le64(record + 40);
        central_offset = le64(record + 48);
        central_end_limit = zip64_offset;
    } else if (disk_number != 0 || central_disk != 0 || entries_on_disk != total_entries) {
        set_error(error, "multi-disk ZIP is unsupported");
        return false;
    }

    if (entries_on_disk != total_entries || total_entries == 0 ||
        central_offset > static_cast<std::uint64_t>(file_size) ||
        central_size > static_cast<std::uint64_t>(file_size) - central_offset ||
        central_offset + central_size > static_cast<std::uint64_t>(central_end_limit)) {
        set_error(error, "ZIP central-directory bounds are inconsistent");
        return false;
    }
    unsigned char signature[4];
    if (!pread_all(fd.get(), signature, sizeof(signature),
                   static_cast<std::int64_t>(central_offset), error)) return false;
    if (le32(signature) != 0x02014b50U) {
        set_error(error, "ZIP central directory does not start at the declared offset");
        return false;
    }
    return true;
}

ArchivePtr open_zip(const char* path, std::string* error) {
    ArchivePtr value(archive_read_new());
    if (!value) {
        set_error(error, "archive_read_new failed");
        return nullptr;
    }
    if (archive_read_support_filter_none(value.get()) != ARCHIVE_OK ||
        archive_read_support_format_zip(value.get()) != ARCHIVE_OK) {
        set_error(error, archive_error(value.get(), "cannot enable ZIP reader"));
        return nullptr;
    }
    if (archive_read_open_filename(value.get(), path, 64 * 1024) != ARCHIVE_OK) {
        set_error(error, archive_error(value.get(), "cannot open ZIP"));
        return nullptr;
    }
    return value;
}

bool scan_zip(const char* path, std::vector<EntryInfo>* entries,
              std::int64_t* total_bytes, std::string* error) {
    entries->clear();
    *total_bytes = 0;
    if (!validate_zip_container(path, error)) return false;
    ArchivePtr value = open_zip(path, error);
    if (!value) return false;

    std::unordered_map<std::string, bool> kinds;
    archive_entry* raw_entry = nullptr;
    int status = ARCHIVE_OK;
    while ((status = archive_read_next_header(value.get(), &raw_entry)) == ARCHIVE_OK) {
        if (entries->size() >= kMaxEntries) {
            set_error(error, "ZIP contains too many entries");
            return false;
        }
        const mode_t type = archive_entry_filetype(raw_entry);
        const bool directory = type == AE_IFDIR;
        if (!directory && type != AE_IFREG) {
            set_error(error, "ZIP contains a non-regular entry");
            return false;
        }
        if (archive_entry_symlink(raw_entry) != nullptr || archive_entry_hardlink(raw_entry) != nullptr) {
            set_error(error, "ZIP contains a link entry");
            return false;
        }
        std::vector<std::string> components;
        std::string normalized;
        if (!normalize_path(archive_entry_pathname(raw_entry), directory, &normalized,
                            &components, error)) return false;
        const auto previous = kinds.find(normalized);
        if (previous != kinds.end()) {
            if (!(directory && previous->second)) {
                set_error(error, "ZIP contains a duplicate or conflicting entry: " + normalized);
                return false;
            }
            // Duplicate directory records are common in generated ZIPs and are harmless.
            // Keep them in the ordered table so the extraction pass can prove that it is
            // reading the exact same header stream as the preflight pass.
            entries->push_back({normalized, 0, true});
            if (archive_read_data_skip(value.get()) != ARCHIVE_OK) {
                set_error(error, archive_error(value.get(), "cannot skip duplicate directory entry"));
                return false;
            }
            continue;
        }
        kinds.emplace(normalized, directory);
        const la_int64_t declared = archive_entry_size(raw_entry);
        if ((!directory && declared < 0) || declared > std::numeric_limits<std::int64_t>::max()) {
            set_error(error, "ZIP entry has an invalid declared size: " + normalized);
            return false;
        }
        const std::int64_t size = directory ? 0 : static_cast<std::int64_t>(declared);
        std::int64_t next_total = 0;
        if (!checked_add(*total_bytes, size, &next_total)) {
            set_error(error, "ZIP declared size overflows int64");
            return false;
        }
        *total_bytes = next_total;
        entries->push_back({normalized, size, directory});
        if (archive_read_data_skip(value.get()) != ARCHIVE_OK) {
            set_error(error, archive_error(value.get(), "cannot skip ZIP entry"));
            return false;
        }
    }
    if (status != ARCHIVE_EOF) {
        set_error(error, archive_error(value.get(), "cannot finish reading ZIP headers"));
        return false;
    }
    if (entries->empty()) {
        set_error(error, "ZIP contains no usable entries");
        return false;
    }
    for (const auto& item : kinds) {
        std::size_t slash = item.first.find('/');
        while (slash != std::string::npos) {
            const std::string parent = item.first.substr(0, slash);
            const auto parent_kind = kinds.find(parent);
            if (parent_kind != kinds.end() && !parent_kind->second) {
                set_error(error, "ZIP uses a regular file as a parent directory: " + item.first);
                return false;
            }
            slash = item.first.find('/', slash + 1);
        }
    }
    return true;
}

bool split_normalized(const std::string& path, std::vector<std::string>* parts) {
    parts->clear();
    std::size_t start = 0;
    while (start < path.size()) {
        const std::size_t slash = path.find('/', start);
        const std::size_t end = slash == std::string::npos ? path.size() : slash;
        parts->push_back(path.substr(start, end - start));
        if (slash == std::string::npos) break;
        start = slash + 1;
    }
    return !parts->empty();
}

bool ensure_dir_at(int parent, const std::string& name, Fd* result, std::string* error) {
    if (mkdirat(parent, name.c_str(), 0755) != 0 && errno != EEXIST) {
        set_error(error, "cannot create destination directory " + name + ": " + std::strerror(errno));
        return false;
    }
    const int fd = openat(parent, name.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        set_error(error, "destination component is not a real directory " + name + ": " + std::strerror(errno));
        return false;
    }
    result->reset(fd);
    return true;
}

bool open_parent(int root, const std::vector<std::string>& parts, Fd* parent,
                 std::string* error) {
    const int duplicate = dup(root);
    if (duplicate < 0) {
        set_error(error, "cannot duplicate destination root fd");
        return false;
    }
    Fd current(duplicate);
    for (std::size_t i = 0; i + 1 < parts.size(); ++i) {
        Fd next;
        if (!ensure_dir_at(current.get(), parts[i], &next, error)) return false;
        current = std::move(next);
    }
    *parent = std::move(current);
    return true;
}

bool write_all(int fd, const char* data, std::size_t length, std::string* error) {
    std::size_t offset = 0;
    while (offset < length) {
        const ssize_t written = write(fd, data + offset, length - offset);
        if (written > 0) {
            offset += static_cast<std::size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) continue;
        set_error(error, "cannot write extraction temp file: " + std::string(std::strerror(errno)));
        return false;
    }
    return true;
}

std::string temp_name() {
    static std::atomic<unsigned long long> counter{0};
    return ".cnzip.tmp." + std::to_string(static_cast<unsigned long long>(getpid())) + "." +
           std::to_string(counter.fetch_add(1, std::memory_order_relaxed));
}

bool report_progress(Progress progress, std::int32_t entries_done,
                     std::int64_t bytes_done, std::string* error) {
    if (progress.fn != nullptr && !progress.fn(progress.opaque, entries_done, bytes_done)) {
        set_error(error, "extraction cancelled");
        return false;
    }
    return true;
}

bool metadata_matches(archive_entry* raw, const EntryInfo& expected, std::string* error) {
    const mode_t type = archive_entry_filetype(raw);
    const bool directory = type == AE_IFDIR;
    std::vector<std::string> parts;
    std::string normalized;
    if (!normalize_path(archive_entry_pathname(raw), directory, &normalized, &parts, error)) return false;
    const la_int64_t raw_size = archive_entry_size(raw);
    const std::int64_t size = directory ? 0 : static_cast<std::int64_t>(raw_size);
    if (directory != expected.directory || normalized != expected.path || (!directory && raw_size < 0) ||
        size != expected.size || archive_entry_symlink(raw) != nullptr ||
        archive_entry_hardlink(raw) != nullptr) {
        set_error(error, "ZIP changed between preflight and extraction: " + expected.path);
        return false;
    }
    return true;
}

}  // namespace

bool list_zip(const char* zip_path, std::vector<EntryInfo>* entries,
              std::int64_t* total_bytes, std::string* error) {
    if (zip_path == nullptr || entries == nullptr || total_bytes == nullptr) {
        set_error(error, "invalid list_zip argument");
        return false;
    }
    return scan_zip(zip_path, entries, total_bytes, error);
}

bool extract_zip(const char* zip_path, const char* destination,
                 std::int64_t max_output_bytes, Progress progress,
                 std::string* error) {
    if (zip_path == nullptr || destination == nullptr || max_output_bytes < 0) {
        set_error(error, "invalid extract_zip argument");
        return false;
    }
    std::vector<EntryInfo> expected;
    std::int64_t declared_total = 0;
    if (!scan_zip(zip_path, &expected, &declared_total, error)) return false;
    if (max_output_bytes > 0 && declared_total > max_output_bytes) {
        set_error(error, "ZIP declared output exceeds extraction limit");
        return false;
    }

    Fd root(open(destination, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (!root) {
        set_error(error, "cannot open extraction root: " + std::string(std::strerror(errno)));
        return false;
    }
    ArchivePtr value = open_zip(zip_path, error);
    if (!value) return false;

    archive_entry* raw = nullptr;
    std::size_t index = 0;
    std::int64_t total_written = 0;
    std::int64_t next_progress = kProgressStep;
    int status = ARCHIVE_OK;
    while ((status = archive_read_next_header(value.get(), &raw)) == ARCHIVE_OK) {
        if (index >= expected.size()) {
            set_error(error, "ZIP gained entries between preflight and extraction");
            return false;
        }
        const EntryInfo& item = expected[index];
        if (!metadata_matches(raw, item, error)) return false;
        // Poll before creating directories or temporary files. This also makes
        // cancellation of a zero-length first entry side-effect free.
        if (!report_progress(progress, static_cast<std::int32_t>(index),
                             total_written, error)) return false;
        std::vector<std::string> parts;
        split_normalized(item.path, &parts);
        Fd parent;
        if (!open_parent(root.get(), parts, &parent, error)) return false;
        const std::string& leaf = parts.back();

        if (item.directory) {
            Fd directory;
            if (!ensure_dir_at(parent.get(), leaf, &directory, error)) return false;
            if (archive_read_data_skip(value.get()) != ARCHIVE_OK) {
                set_error(error, archive_error(value.get(), "cannot skip directory payload"));
                return false;
            }
        } else {
            struct stat existing {};
            if (fstatat(parent.get(), leaf.c_str(), &existing, AT_SYMLINK_NOFOLLOW) == 0) {
                if (!S_ISREG(existing.st_mode)) {
                    set_error(error, "destination target is not a regular file: " + item.path);
                    return false;
                }
            } else if (errno != ENOENT) {
                set_error(error, "cannot inspect destination target: " + item.path);
                return false;
            }

            std::string temp;
            Fd output;
            for (int attempt = 0; attempt < 64; ++attempt) {
                temp = temp_name();
                const int fd = openat(parent.get(), temp.c_str(),
                                      O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
                if (fd >= 0) {
                    output.reset(fd);
                    break;
                }
                if (errno != EEXIST) break;
            }
            if (!output) {
                set_error(error, "cannot create extraction temp file for " + item.path + ": " +
                                 std::strerror(errno));
                return false;
            }

            bool committed = false;
            auto cleanup = [&]() {
                output.reset();
                if (!committed && !temp.empty()) unlinkat(parent.get(), temp.c_str(), 0);
            };
            std::int64_t entry_written = 0;
            char buffer[64 * 1024];
            while (true) {
                const la_ssize_t got = archive_read_data(value.get(), buffer, sizeof(buffer));
                if (got == 0) break;
                if (got < 0) {
                    set_error(error, archive_error(value.get(), "cannot read ZIP entry data"));
                    cleanup();
                    return false;
                }
                std::int64_t next_entry = 0;
                std::int64_t next_total = 0;
                if (!checked_add(entry_written, static_cast<std::int64_t>(got), &next_entry) ||
                    !checked_add(total_written, static_cast<std::int64_t>(got), &next_total) ||
                    next_entry > item.size ||
                    (max_output_bytes > 0 && next_total > max_output_bytes)) {
                    set_error(error, "ZIP entry exceeds its declared size or extraction limit: " + item.path);
                    cleanup();
                    return false;
                }
                // cancel-before-write: poll before any bytes reach the temporary file.
                if (!report_progress(progress, static_cast<std::int32_t>(index), next_total, error)) {
                                        cleanup();
                                        return false;
                                    }
                if (!write_all(output.get(), buffer, static_cast<std::size_t>(got), error)) {
                    cleanup();
                    return false;
                }
                entry_written = next_entry;
                total_written = next_total;
                if (total_written >= next_progress) {
                    if (!report_progress(progress, static_cast<std::int32_t>(index), total_written, error)) {
                        cleanup();
                        return false;
                    }
                    while (next_progress <= total_written &&
                           next_progress <= std::numeric_limits<std::int64_t>::max() - kProgressStep) {
                        next_progress += kProgressStep;
                    }
                }
            }
            if (entry_written != item.size) {
                set_error(error, "ZIP entry ended before its declared size: " + item.path);
                cleanup();
                return false;
            }
            if (fchmod(output.get(), 0644) != 0 || fsync(output.get()) != 0) {
                set_error(error, "cannot sync extraction temp file: " + item.path);
                cleanup();
                return false;
            }
            // One final cancellation poll before the atomic replacement. No
            // bytes have reached the live target yet, including for empty files.
            if (!report_progress(progress, static_cast<std::int32_t>(index),
                                 total_written, error)) {
                cleanup();
                return false;
            }
            output.reset();
            if (renameat(parent.get(), temp.c_str(), parent.get(), leaf.c_str()) != 0) {
                set_error(error, "cannot atomically install extracted file " + item.path + ": " +
                                 std::strerror(errno));
                cleanup();
                return false;
            }
            committed = true;
        }
        ++index;
        if (!report_progress(progress, static_cast<std::int32_t>(index), total_written, error)) return false;
    }
    if (status != ARCHIVE_EOF) {
        set_error(error, archive_error(value.get(), "ZIP extraction ended with an archive error"));
        return false;
    }
    if (index != expected.size() || total_written != declared_total) {
        set_error(error, "ZIP extraction result does not match preflight metadata");
        return false;
    }
    return true;
}

}  // namespace cnzip

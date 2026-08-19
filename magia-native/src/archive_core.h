#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace cnzip {

struct EntryInfo {
    std::string path;
    std::int64_t size;
    bool directory;
};

using ProgressFn = bool (*)(void* opaque, std::int32_t entries_done,
                            std::int64_t bytes_done);

struct Progress {
    ProgressFn fn = nullptr;
    void* opaque = nullptr;
};

bool list_zip(const char* zip_path, std::vector<EntryInfo>* entries,
              std::int64_t* total_bytes, std::string* error);

bool extract_zip(const char* zip_path, const char* destination,
                 std::int64_t max_output_bytes, Progress progress,
                 std::string* error);

}  // namespace cnzip

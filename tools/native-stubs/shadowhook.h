#pragma once
extern "C" {
enum { SHADOWHOOK_MODE_SHARED=0, SHADOWHOOK_MODE_UNIQUE=1 };
int shadowhook_init(int mode, bool debuggable);
void* shadowhook_hook_sym_name(const char* lib, const char* sym, void* newaddr, void** origaddr);
void* shadowhook_hook_sym_addr(void* addr, void* newaddr, void** origaddr);
int shadowhook_unhook(void* stub);
const char* shadowhook_get_version();
int shadowhook_get_errno();
enum { SHADOWHOOK_ERRNO_PENDING = 12 };
const char* shadowhook_to_errmsg(int errno_);
}

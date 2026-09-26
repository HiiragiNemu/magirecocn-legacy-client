package io.kamihama.magianative;

/**
 * 独立公开资源仓的固定信任锚。源代码仓私有化不能让客户端依赖登录凭证。
 * 只增加公开传输入口，不替换 CNEndpoints.ASSETS_BASE 的安装标记身份，
 * 不改变现有镜像、资源文件名、字体路径、下载校验或版本比较规则。
 */
final class CNPublicResources {
    // 194只发布字体；195迁移入口保持关闭，后续独立启用并验收。
    static final boolean ENABLED = false;
    static final String RELEASE_BASE =
            "https://github.com/HiiragiNemu/ProgettoMagius-1/releases/download/latest/";
    static final String CONFIG_URL =
            "https://raw.githubusercontent.com/HiiragiNemu/ProgettoMagius-1/main/legacy/config.json";

    private CNPublicResources() {}
}

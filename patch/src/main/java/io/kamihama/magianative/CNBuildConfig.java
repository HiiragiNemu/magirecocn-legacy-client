package io.kamihama.magianative;

/**
 * 构建期配置。源码里只留**默认值**，真实取值由 CI 在编译前注入
 * （build-apk.yml 的「主引擎」选择框 → sed 改写 {@link #MAIN_ENGINE}）。
 *
 * <p>与 {@link CNEndpoints} 的分工不同：那两个值是 <b>fail-closed</b> 的空串，
 * 注入失败宁可退化成「什么都下不了」；这里是「默认值 + 可选注入」——不注入
 * 也编得出、跑得好，只是引擎是默认的 {@code self}。
 *
 * <p>⚠ 别把注入后的值提交进仓库：{@code MAIN_ENGINE} 一旦在库里变成
 * {@code aria2c}，就再也区分不了「构建选过」与「库里写死」。
 */
public final class CNBuildConfig {
    private CNBuildConfig() {}

    /**
     * 主下载引擎，构建期注入。源码里恒为 {@code "self"}：
     *
     * <ul>
     *   <li>{@code "self"} —— 自建分块引擎为主。aria2 仅当云端
     *       {@code config.json} 的 {@code force_aria2} 或调试开关
     *       {@code useAria2} 打开时才先用一把（备用引擎）；</li>
     *   <li>{@code "aria2c"} —— 进程内 aria2c 引擎为主：每个文件先走它，
     *       失败回退自建引擎整份重下。</li>
     * </ul>
     *
     * <p>注意它是<b>编译期常量</b>：javac 会把引用它的地方在编译时内联，
     * 所以注入必须发生在 javac 之前（build-apk.yml 的注入步骤就在编译前）。
     */
    public static final String MAIN_ENGINE = "self";

    /** 当前发布关闭所有文件访问；源码保留，只有显式构建启用才显示入口并声明权限。 */
    public static final boolean ALL_FILES_ACCESS = false;
}

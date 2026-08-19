.class public Lio/kamihama/magianative/RestClient;
.super Ljava/lang/Object;
.source "RestClient.java"


# static fields
.field private static final JSON:Lokhttp3/MediaType;

.field private static http1Client:Lokhttp3/OkHttpClient;


# instance fields
.field private final Endpoint:Ljava/lang/String;

.field private final LogTag:Ljava/lang/String;

.field private UserAgent:Ljava/lang/String;

.field private client:Lokhttp3/OkHttpClient;


# direct methods
.method static constructor <clinit>()V
    .locals 1

    .prologue
    const-string v0, "application/json; charset=utf-8"

    invoke-static {v0}, Lokhttp3/MediaType;->parse(Ljava/lang/String;)Lokhttp3/MediaType;

    move-result-object v0

    sput-object v0, Lio/kamihama/magianative/RestClient;->JSON:Lokhttp3/MediaType;

    return-void
.end method

.method public static getCurrentActivity()Landroid/app/Activity;
    .locals 1

    # F-053：选择「当前前台 Activity」的健壮逻辑（遍历 mActivities、按生命周期
    # 状态挑选、非 finishing/destroyed）移到了 Java 侧 CNRestClientActivity——
    # 基线 smali 只留一行委托。为什么不在 smali 里写：这段逻辑要遍历 + 多字段
    # 判断，Java 里可读可测；RestClient.smali 本就是 baseline/replace 整份替换
    # 文件，逻辑进 Java 后这里只是薄委托（见 README「基线与补丁」）。
    invoke-static {}, Lio/kamihama/magianative/CNRestClientActivity;->getCurrentActivity()Landroid/app/Activity;

    move-result-object v0

    return-object v0
.end method

.method public static startCNDownload()V
    .locals 0

    invoke-static {}, Lio/kamihama/magianative/CNDownloaderFix;->runInstaller()V

    return-void

.end method
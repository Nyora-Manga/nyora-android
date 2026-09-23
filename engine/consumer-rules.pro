# The data-driven engine resolves every source from catalogue data: no reflection,
# no service loaders and no dynamically loaded code, so it needs no keep rules of
# its own. Its third-party surface (OkHttp, jsoup, org.json) is kept by the
# consuming application's proguard-rules.pro.

package automix

import androidx.compose.runtime.CompositionLocal

private const val COMPOSER = "Landroidx/compose/runtime/Composer;"
private const val NAV_STACK = "Landroidx/navigation3/runtime/NavBackStack;"
private const val NAV_LOCAL = "Landroidx/compose/runtime/ProvidableCompositionLocal;"

internal data class HostUiSite(val target: HostSymbol, val caller: String) {
    val callerClass: String get() = caller.replace('/', '.')
}

/** Navigation and sidebar discovery follows SPW Listen Stats' structural selectors (MIT). */
internal class HostNavigationTargets(symbols: HostSymbols) {
    private val content = symbols.methods.filter { body ->
        body.symbol.descriptor == "(${COMPOSER}I)V" && body.calls.any {
            it.owner.startsWith("androidx/navigation3/ui/") && COMPOSER in it.descriptor
        }
    }.uniqueHost("原生导航内容")
    private val root = symbols.actual(content.symbol.owner).method("原生导航容器") { body ->
        body.isStatic && body.symbol.descriptor == content.symbol.descriptor &&
            body.calls.any { it.owner == "androidx/compose/animation/SharedTransitionScopeKt" }
    }.symbol
    val page = HostUiSite(root, symbols.methods.filter { body ->
        body.symbol.owner != root.owner && COMPOSER in body.symbol.descriptor && root in body.calls
    }.map { it.symbol.owner }.distinct().uniqueHost("导航容器调用位置")).also {
        symbols.checkSite(it)
        symbols.method(it.target)
    }

    fun contentLayout(symbols: HostSymbols): HostUiSite {
        val layout = checkNotNull(symbols.body(root)).calls.filter {
            it.owner == "androidx/compose/animation/SharedTransitionScopeKt" &&
                it.name == "SharedTransitionLayout" &&
                it.descriptor == "(Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function3;${COMPOSER}II)V"
        }.uniqueHost("宿主页面内容容器")
        return HostUiSite(layout, root.owner).also(symbols::checkSite)
    }

    private val stackGetter = content.calls.filter { it.descriptor == "()$NAV_STACK" }.distinct()
        .uniqueHost("宿主导航栈读取方法")
    private val navType = symbols.actual(stackGetter.owner)
    private val stackKey = navType.field("宿主导航栈") { field, _ -> field.descriptor == NAV_STACK }
    val stack = symbols.field(stackKey)
    private val navigatorLocal = symbols.field(content.reads.filter {
        it.owner == root.owner && it.descriptor == NAV_LOCAL
    }.distinct().uniqueHost("导航 CompositionLocal"))

    private val operations = navType.methods.filter { body ->
        !body.isStatic && body.isPublic && body.symbol.returns == "V" && stackKey in body.reads
    }
    private fun HostMethod.addsRoute() = calls.any { it.owner == "androidx/navigation3/runtime/NavBackStack" && it.name == "add" }
    private val back = operations.filter { body ->
        body.symbol.arguments.isEmpty() && !body.addsRoute() &&
            body.calls.any { it.owner == "kotlin/collections/CollectionsKt" && it.name == "removeLastOrNull" }
    }.uniqueHost("宿主返回")
    val backMethod = symbols.method(back.symbol)
    private val navigate = operations.filter { body ->
        body.symbol.arguments.size == 1 && body.addsRoute() &&
            body.calls.any { it.owner == "kotlin/jvm/internal/Intrinsics" && it.name == "areEqual" }
    }.uniqueHost("宿主导航")
    val navigateMethod = symbols.method(navigate.symbol)
    val replaceMethod = symbols.method(operations.filter {
        it !== navigate && it.addsRoute() && it.symbol.descriptor == navigate.symbol.descriptor
    }.uniqueHost("宿主替换页面").symbol)
    val forwardMethod = symbols.method(operations.filter {
        it.addsRoute() && it.symbol.arguments.isEmpty()
    }.uniqueHost("宿主前进").symbol)

    private val navClass = symbols.resolve(navType)
    private val forwardEntries = runCatching {
        navClass.declaredFields.singleOrNull {
            it.type == java.util.List::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.takeIf { it.trySetAccessible() }
    }.getOrNull()

    fun forwardHistory(navigator: Any): List<Any?>? = runCatching {
        (forwardEntries?.get(navigator) as? List<*>)?.toList()
    }.getOrNull()

    private val dialog = symbols.method(back.calls.filter {
        it.descriptor == "(Landroidx/navigation3/runtime/NavKey;)Z" && symbols.body(it)?.isStatic == true
    }.distinct().uniqueHost("宿主弹窗判定"))
    private val playerState = back.reads.filter { it.owner == navType.owner && it.descriptor.startsWith("L") }
        .distinct().flatMap { field ->
            back.calls.filter {
                field.descriptor == "L${it.owner};" && it.descriptor == "()Z" && symbols.body(it)?.isStatic == false
            }.distinct().map { field to it }
        }.uniqueHost("宿主播放器展开状态")
    private val player = symbols.field(playerState.first)
    private val expanded = symbols.method(playerState.second)

    @Suppress("UNCHECKED_CAST")
    val localNavigator: CompositionLocal<Any?> get() = navigatorLocal.get(null) as CompositionLocal<Any?>

    fun hasOverlay(navigator: Any, route: Any?): Boolean =
        (route != null && dialog.invoke(null, route) == true) || expanded.invoke(player.get(navigator)) == true
}

internal class HostSidebarTargets(symbols: HostSymbols) {
    private fun rowControl(symbols: HostSymbols, method: HostSymbol): Boolean = method.arguments.let { args ->
        args.size == 11 && args.take(5) == listOf("Ljava/lang/String;", "Z", "Landroidx/compose/ui/Modifier;",
            "Z", "Landroidx/compose/ui/Modifier;") &&
            args.takeLast(4) == listOf("Lkotlin/jvm/functions/Function3;", COMPOSER, "I", "I") &&
            method.returns == "V" && symbols.body(method)?.isStatic == true
    }

    private val row = symbols.methods.filter { body ->
        body.isStatic && body.symbol.arguments.size == 3 && body.symbol.arguments.takeLast(2) == listOf(COMPOSER, "I") &&
            body.calls.any { rowControl(symbols, it) } &&
            body.calls.any { it.owner == "org/jetbrains/compose/resources/StringResourcesKt" && it.name == "stringResource" }
    }.uniqueHost("侧栏原生路由行")
    val control = symbols.method(row.calls.filter { rowControl(symbols, it) }.distinct().uniqueHost("侧栏行控件"))
    val routeGetter = symbols.method(row.calls.filter {
        "L${it.owner};" == row.symbol.arguments.first() && it.arguments.isEmpty() && it.returns.startsWith("L")
    }.distinct().uniqueHost("侧栏对应路由"))
    private val accessor = symbols.actual(row.symbol.owner).method("侧栏行访问入口") {
        it.isPublic && it.isStatic && it.symbol.descriptor == row.symbol.descriptor && row.symbol in it.calls
    }.symbol
    val entry = HostUiSite(accessor, symbols.methods.filter { body ->
        body.symbol.owner != accessor.owner && accessor in body.calls &&
            body.symbol.descriptor == "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
    }.map { it.symbol.owner }.distinct().uniqueHost("侧栏行调用位置")).also(symbols::checkSite)
    val routeAccessor = symbols.method(accessor)
}

private fun HostSymbols.checkSite(site: HostUiSite) {
    val count = actual(site.caller).methods.sumOf { method -> method.calls.count { it == site.target } }
    check(count == 1) { "宿主界面调用点不唯一 · ${site.callerClass} (${count} 处)" }
}

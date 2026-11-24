package io.github.deserthouse.opticon.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 构造期启动协程专用：保证协程体在构造完成之后才开始。
 *
 * viewModelScope 用的是 Dispatchers.Main.immediate——在构造函数里 launch，协程体会就地开始执行。
 * 里面的 withContext(Dispatchers.IO) 在后台那段工作完成得足够快时并不挂起、就地继续往下跑，
 * 于是类里声明在 init 之后的属性此时还是 null，一写就空指针。
 * （2026-11-24 设备验证套件实测：打开设置页时应用因此崩溃。线程调度决定的概率事件，
 * 平时几乎不出现，宿主负载高时才撞得上。）
 *
 * 显式用不带 immediate 的 Dispatchers.Main：总是先投递再执行，协程体最早也要等当前这一帧
 * （也就是构造函数）跑完才运行。ViewModel 的 init 块里启动协程一律走这里。
 */
internal fun ViewModel.launchAfterInit(
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    block: suspend CoroutineScope.() -> Unit
): Job = viewModelScope.launch(dispatcher, block = block)

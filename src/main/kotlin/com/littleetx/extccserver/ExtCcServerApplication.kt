package com.littleetx.extccserver

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger


@Component
class QpsManager(
    @Value("\${trainticket.qps.limit:100}")
    private var _maxQps: Int,
) {
    private var _cnt = AtomicInteger(_maxQps)
    private var _qps = AtomicInteger(0)
    private var logger = LoggerFactory.getLogger(QpsManager::class.java)

    init {
        logger.info("QpsManager init, default qps: $_maxQps")
    }

    var maxQps: Int
        get() = _maxQps
        set(value) {
            _cnt.addAndGet(value - _maxQps)
            _maxQps = value
        }

    val qps: Int
        get() = _qps.get()

    fun execute(block: (Boolean) -> Unit) = runBlocking {
        _qps.incrementAndGet()
        launch {
            delay(1000)
            _qps.decrementAndGet()
        }
        if (_cnt.getAndDecrement() > 0) {
            launch {
                delay(1000)
                _cnt.incrementAndGet()
            }
            block(true)
        } else {
            _cnt.incrementAndGet()
            block(false)
        }
    }
}


@Configuration
@ComponentScan("com.littleetx.extccserver")
class TestFilter(private val qpsManager: QpsManager) : Filter {

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        if (request !is HttpServletRequest) {
            chain.doFilter(request, response)
            return
        }
        qpsManager.execute { canHandle ->
            if (canHandle) {
                chain.doFilter(request, response)
            } else {
                response as HttpServletResponse
                response.status = 503
                response.writer.write("Service Unavailable")
            }
        }
    }
}

@Component
class QpsManagerLinker(
    @Value("\${trainticket.qps.server.host:localhost}")
    host: String,
    @Value("\${trainticket.qps.server.port:32012}")
    port: Int,
    @Value("\${trainticket.qps.server.interval:1000}")
    val interval: Long,
    private val qpsManager: QpsManager,
) {
    private val logger = LoggerFactory.getLogger(QpsManagerLinker::class.java)

    private fun Socket.handle() {
        logger.info("Connected to QpsManager server")
        Thread {
            try {
                val reader = getInputStream().bufferedReader()
                while (true) {
                    val line = reader.readLine()
                    val parts = line.split(":")
                    if (parts.size != 2) {
                        logger.warn("Invalid message from QpsManager server: $line")
                        continue
                    }
                    if (parts[0] != "MaxQPS") {
                        logger.warn("Unrecognized key: ${parts[0]}")
                        continue
                    }
                    val qps = parts[1].toIntOrNull()
                    if (qps == null) {
                        logger.warn("Invalid qps from QpsManager server: ${parts[1]}")
                        continue
                    }
                    qpsManager.maxQps = qps
                }
            } catch (e: Exception) {
                logger.warn("Failed to read from QpsManager server")
            }
        }.start()
        Thread {
            try {
                val writer = getOutputStream().bufferedWriter()
                while (true) {
                    Thread.sleep(interval)
                    writer.write("QPS:${qpsManager.qps}\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                logger.warn("Failed to write to QpsManager server")
            }
        }.start()
    }

    init {
        try {
            Socket(host, port).handle()
        } catch (e: Exception) {
            logger.warn("Failed to connect to QpsManager server, qps will be fixed to ${qpsManager.maxQps}")
        }
    }
}
package com.littleetx.extccserver

import com.sun.management.OperatingSystemMXBean
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
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random


@Component
class QpsManager {
    private var _qps = AtomicInteger(0)
    private var logger = LoggerFactory.getLogger(QpsManager::class.java)

    final var maxQpsRate: Double = 1.0

    init {
        logger.info("QpsManager init, default qps rate: $maxQpsRate")
    }

    val qps: Int
        get() = _qps.get()

    fun execute(block: (Boolean) -> Unit) = runBlocking {
        _qps.incrementAndGet()
        launch {
            delay(1000)
            _qps.decrementAndGet()
        }
        val canExecute = Random.nextDouble() < maxQpsRate
        block(canExecute)
    }
}


@Configuration
@ComponentScan("com.littleetx.extccserver")
class QpsFilter(private val qpsManager: QpsManager) : Filter {

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
@AutoConfigureAfter(QpsManager::class)
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
                    if (parts[0] != "MaxQpsRate") {
                        logger.warn("Unrecognized key: ${parts[0]}")
                        continue
                    }
                    val qpsRate = parts[1].toDoubleOrNull()
                    if (qpsRate == null) {
                        logger.warn("Invalid qps from QpsManager server: ${parts[1]}")
                        continue
                    }
                    qpsManager.maxQpsRate = qpsRate
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
                    val bean = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
                    writer.write("QPS:${qpsManager.qps}\n")
                    writer.write("CPU:${bean.cpuLoad}\n")
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
            logger.warn("Failed to connect to QpsManager server ${host}:${port}, max qps rate qps will be fixed to ${qpsManager.maxQpsRate}")
        }
    }
}
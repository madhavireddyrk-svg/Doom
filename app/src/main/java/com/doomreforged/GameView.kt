package com.doomreforged

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var renderThread: Thread? = null
    @Volatile private var running = false

    private val world = WorldState()
    private val skyPaint = Paint().apply { color = Color.rgb(18, 16, 38) }
    private val floorPaint = Paint().apply { color = Color.rgb(44, 32, 28) }
    private val wallPaint = Paint().apply { isAntiAlias = false }
    private val hudPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        isAntiAlias = true
    }
    private val joystickBasePaint = Paint().apply {
        color = Color.argb(70, 220, 220, 220)
        style = Paint.Style.FILL
    }
    private val joystickKnobPaint = Paint().apply {
        color = Color.argb(180, 255, 120, 80)
        style = Paint.Style.FILL
    }
    private val shootButtonPaint = Paint().apply {
        color = Color.argb(160, 220, 30, 30)
        style = Paint.Style.FILL
    }
    private val enemyPaint = Paint().apply {
        color = Color.rgb(190, 40, 45)
        isAntiAlias = true
    }
    private val weaponPaint = Paint().apply {
        color = Color.rgb(90, 90, 95)
        style = Paint.Style.FILL
    }
    private val muzzleFlashPaint = Paint().apply {
        color = Color.argb(220, 255, 200, 80)
        style = Paint.Style.FILL
    }

    private var frameTimeNanos = System.nanoTime()

    private val moveJoystick = JoystickState()
    private var shootPointer = -1
    private var firing = false

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = pause()

    fun resume() {
        if (running) return
        running = true
        renderThread = Thread(this, "doom-prototype-render").also { it.start() }
    }

    fun pause() {
        running = false
        renderThread?.join()
        renderThread = null
    }

    override fun run() {
        while (running) {
            if (!holder.surface.isValid) continue
            val now = System.nanoTime()
            val dt = ((now - frameTimeNanos) / 1_000_000_000.0f).coerceIn(0f, 0.05f)
            frameTimeNanos = now

            update(dt)
            val canvas = holder.lockCanvas()
            render(canvas)
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun update(dt: Float) {
        val moveMagnitude = sqrt(moveJoystick.dx * moveJoystick.dx + moveJoystick.dy * moveJoystick.dy)
        val moveScale = (moveMagnitude / moveJoystick.maxRadius).coerceIn(0f, 1f)
        val angle = atan2(moveJoystick.dy.toDouble(), moveJoystick.dx.toDouble()).toFloat()

        val strafe = cos(angle) * moveScale
        val forward = -sin(angle) * moveScale

        val moveSpeed = 2.4f
        world.player.tryMove(strafe * moveSpeed * dt, forward * moveSpeed * dt, world.map)

        if (moveJoystick.lookDelta != 0f) {
            world.player.direction += moveJoystick.lookDelta * dt * 2.1f
        }

        world.updateEnemies(dt)

        if (firing) {
            world.shoot()
            firing = false
        }

        world.weaponKickback = (world.weaponKickback - dt * 5.5f).coerceAtLeast(0f)
    }

    private fun render(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h * 0.52f, skyPaint)
        canvas.drawRect(0f, h * 0.52f, w, h, floorPaint)

        val projectionPlane = w / (2f * kotlin.math.tan((world.player.fov * 0.5f).toDouble()).toFloat())

        for (x in 0 until width step 2) {
            val cameraX = (2f * x / w) - 1f
            val rayAngle = world.player.direction + cameraX * world.player.fov * 0.55f
            val hit = castRay(world.player.x, world.player.y, rayAngle)
            val correctedDist = hit.distance * cos((rayAngle - world.player.direction).toDouble()).toFloat().coerceAtLeast(0.0001f)
            val wallHeight = (projectionPlane / correctedDist) * 0.75f
            val startY = (h - wallHeight) * 0.5f
            val endY = startY + wallHeight
            val shade = (220f / (1f + correctedDist * 0.38f)).toInt().coerceIn(35, 255)
            wallPaint.color = when (hit.tile) {
                2 -> Color.rgb(shade / 2, shade, shade / 2)
                3 -> Color.rgb(shade, shade / 2, shade / 2)
                else -> Color.rgb(shade, shade, shade)
            }
            canvas.drawLine(x.toFloat(), startY, x.toFloat(), endY, wallPaint)
        }

        renderEnemies(canvas, projectionPlane)
        renderWeapon(canvas)
        renderHud(canvas)
        renderControls(canvas)
    }

    private fun renderEnemies(canvas: Canvas, projectionPlane: Float) {
        val h = height.toFloat()
        val sorted = world.enemies.sortedByDescending { enemy ->
            val dx = enemy.x - world.player.x
            val dy = enemy.y - world.player.y
            dx * dx + dy * dy
        }

        sorted.forEach { enemy ->
            if (!enemy.alive) return@forEach
            val dx = enemy.x - world.player.x
            val dy = enemy.y - world.player.y
            val distance = sqrt(dx * dx + dy * dy)
            val angleToEnemy = atan2(dy.toDouble(), dx.toDouble()).toFloat()
            var relativeAngle = angleToEnemy - world.player.direction

            while (relativeAngle > PI) relativeAngle -= (2f * PI).toFloat()
            while (relativeAngle < -PI) relativeAngle += (2f * PI).toFloat()

            if (kotlin.math.abs(relativeAngle) > world.player.fov * 0.55f) return@forEach

            val screenX = width * (0.5f + (relativeAngle / (world.player.fov * 1.1f)))
            val size = (projectionPlane / distance) * 0.75f
            val top = h * 0.5f - size * 0.5f
            val rect = RectF(screenX - size * 0.32f, top, screenX + size * 0.32f, top + size)
            enemyPaint.alpha = (255f / (1f + distance * 0.2f)).toInt().coerceIn(90, 255)
            canvas.drawRoundRect(rect, 8f, 8f, enemyPaint)
        }
    }

    private fun renderHud(canvas: Canvas) {
        canvas.drawText("HP ${world.player.health}", 26f, 42f, hudPaint)
        canvas.drawText("AMMO ${world.player.ammo}", 26f, 82f, hudPaint)
        canvas.drawText("KILLS ${world.kills}", 26f, 122f, hudPaint)

        if (world.storyModeCleared) {
            hudPaint.textSize = 44f
            canvas.drawText("TO BE CONTINUED...", width * 0.28f, 64f, hudPaint)
            hudPaint.textSize = 34f
        }
    }

    private fun renderWeapon(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val recoil = world.weaponKickback * 48f
        val baseY = h - 110f + recoil

        val path = Path()
        path.moveTo(w * 0.42f, baseY)
        path.lineTo(w * 0.58f, baseY)
        path.lineTo(w * 0.62f, h)
        path.lineTo(w * 0.38f, h)
        path.close()
        canvas.drawPath(path, weaponPaint)

        if (world.weaponKickback > 0.35f) {
            canvas.drawCircle(w * 0.5f, baseY - 12f, 24f + recoil * 0.3f, muzzleFlashPaint)
        }
    }

    private fun renderControls(canvas: Canvas) {
        val baseX = width * 0.16f
        val baseY = height * 0.77f
        val radius = min(width, height) * 0.11f
        moveJoystick.baseX = baseX
        moveJoystick.baseY = baseY
        moveJoystick.maxRadius = radius

        val knobX = baseX + moveJoystick.dx.coerceIn(-radius, radius)
        val knobY = baseY + moveJoystick.dy.coerceIn(-radius, radius)

        canvas.drawCircle(baseX, baseY, radius, joystickBasePaint)
        canvas.drawCircle(knobX, knobY, radius * 0.42f, joystickKnobPaint)

        val shootX = width * 0.88f
        val shootY = height * 0.78f
        val shootRadius = min(width, height) * 0.09f
        moveJoystick.shootRect.set(
            shootX - shootRadius,
            shootY - shootRadius,
            shootX + shootRadius,
            shootY + shootRadius
        )
        canvas.drawCircle(shootX, shootY, shootRadius, shootButtonPaint)
        canvas.drawText("FIRE", shootX - 34f, shootY + 10f, hudPaint)
    }

    private fun castRay(startX: Float, startY: Float, angle: Float): RayHit {
        val step = 0.02f
        val dx = cos(angle) * step
        val dy = sin(angle) * step
        var x = startX
        var y = startY
        var travelled = 0f

        while (travelled < 20f) {
            x += dx
            y += dy
            travelled += step
            val tile = world.map.tileAt(x, y)
            if (tile > 0) return RayHit(travelled, tile)
        }
        return RayHit(20f, 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                val pointerId = event.getPointerId(index)

                if (moveJoystick.shootRect.contains(x, y)) {
                    shootPointer = pointerId
                    firing = true
                } else {
                    moveJoystick.pointerId = pointerId
                    moveJoystick.active = true
                    moveJoystick.updateFromTouch(x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id == moveJoystick.pointerId) {
                        moveJoystick.updateFromTouch(event.getX(i), event.getY(i))
                    }
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)

                if (pointerId == moveJoystick.pointerId) {
                    moveJoystick.reset()
                }

                if (pointerId == shootPointer) {
                    shootPointer = -1
                }
            }
        }
        return true
    }
}

data class RayHit(val distance: Float, val tile: Int)

class JoystickState {
    var pointerId = -1
    var baseX = 0f
    var baseY = 0f
    var dx = 0f
    var dy = 0f
    var maxRadius = 1f
    var active = false
    var lookDelta = 0f
    val shootRect = RectF()

    fun updateFromTouch(x: Float, y: Float) {
        dx = x - baseX
        dy = y - baseY
        val magnitude = sqrt(dx * dx + dy * dy)
        if (magnitude > maxRadius) {
            val scale = maxRadius / magnitude
            dx *= scale
            dy *= scale
        }
        lookDelta = (dx / maxRadius).coerceIn(-1f, 1f)
    }

    fun reset() {
        pointerId = -1
        dx = 0f
        dy = 0f
        lookDelta = 0f
        active = false
    }
}

class WorldState {
    val map = GridMap(
        arrayOf(
            intArrayOf(1,1,1,1,1,1,1,1,1,1,1,1),
            intArrayOf(1,0,0,0,0,0,0,0,0,0,0,1),
            intArrayOf(1,0,0,0,1,1,1,0,0,0,0,1),
            intArrayOf(1,0,2,0,0,0,1,0,3,0,0,1),
            intArrayOf(1,0,0,0,0,0,1,0,0,0,0,1),
            intArrayOf(1,0,1,1,1,0,0,0,1,1,0,1),
            intArrayOf(1,0,0,0,1,0,0,0,0,1,0,1),
            intArrayOf(1,0,3,0,0,0,1,0,0,1,0,1),
            intArrayOf(1,0,0,0,0,0,1,0,0,0,0,1),
            intArrayOf(1,0,0,1,1,0,0,0,1,0,0,1),
            intArrayOf(1,0,0,0,0,0,0,0,0,0,0,1),
            intArrayOf(1,1,1,1,1,1,1,1,1,1,1,1)
        )
    )

    val player = Player(2.5f, 2.5f, 0f, (PI * 0.64).toFloat())
    val enemies = mutableListOf(
        Enemy(6.5f, 2.7f), Enemy(8.2f, 7.8f), Enemy(3.4f, 8.3f), Enemy(9.5f, 3.1f)
    )
    var kills = 0
    var weaponKickback = 0f
    var storyModeCleared = false

    fun updateEnemies(dt: Float) {
        enemies.forEach { enemy ->
            if (!enemy.alive) return@forEach
            val dx = player.x - enemy.x
            val dy = player.y - enemy.y
            val distance = sqrt(dx * dx + dy * dy)
            if (distance > 0.85f) {
                val nx = dx / distance
                val ny = dy / distance
                val speed = 0.55f
                val nextX = enemy.x + nx * dt * speed
                val nextY = enemy.y + ny * dt * speed
                if (map.tileAt(nextX, nextY) == 0) {
                    enemy.x = nextX
                    enemy.y = nextY
                }
            } else {
                player.health = (player.health - 1).coerceAtLeast(0)
            }
        }
        if (kills == enemies.size) {
            storyModeCleared = true
        }
    }

    fun shoot() {
        if (player.ammo <= 0 || player.health <= 0) return
        player.ammo -= 1
        weaponKickback = 1f

        val target = enemies
            .filter { it.alive }
            .minByOrNull { enemy ->
                val dx = enemy.x - player.x
                val dy = enemy.y - player.y
                val distance = sqrt(dx * dx + dy * dy)
                val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                kotlin.math.abs(angle - player.direction) + distance * 0.2f
            }

        target?.let {
            val dx = it.x - player.x
            val dy = it.y - player.y
            val distance = sqrt(dx * dx + dy * dy)
            val angle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
            val diff = kotlin.math.abs(angle - player.direction)
            if (distance < 7f && diff < 0.28f) {
                it.alive = false
                kills += 1
                player.ammo += 2
            }
        }
    }
}

class GridMap(private val grid: Array<IntArray>) {
    fun tileAt(x: Float, y: Float): Int {
        val gx = x.toInt()
        val gy = y.toInt()
        if (gy !in grid.indices || gx !in grid[gy].indices) return 1
        return grid[gy][gx]
    }
}

data class Player(
    var x: Float,
    var y: Float,
    var direction: Float,
    val fov: Float,
    var health: Int = 100,
    var ammo: Int = 30
) {
    fun tryMove(strafe: Float, forward: Float, map: GridMap) {
        val moveX = x + cos(direction) * forward + cos(direction + (PI / 2f).toFloat()) * strafe
        val moveY = y + sin(direction) * forward + sin(direction + (PI / 2f).toFloat()) * strafe
        if (map.tileAt(moveX, y) == 0) x = moveX
        if (map.tileAt(x, moveY) == 0) y = moveY
    }
}

data class Enemy(var x: Float, var y: Float, var alive: Boolean = true)

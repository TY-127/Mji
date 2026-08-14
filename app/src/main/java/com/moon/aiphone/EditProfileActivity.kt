package com.moon.aiphone

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yalantis.ucrop.UCrop
import java.io.File

class EditProfileActivity : AppCompatActivity() {
    private var myAvatarUri: Uri? = null

    // ⚡ 第二步：在手术台旁边端着盘子，接住切好的完美头像！
    private val cropImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                myAvatarUri = resultUri
                try {
                    val inputStream = contentResolver.openInputStream(resultUri)
                    val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                    findViewById<ImageView>(R.id.ivEditMyAvatar).setImageBitmap(bitmap)
                    inputStream?.close()
                } catch (e: Exception) {}
            }
        }
    }

    // ⚡ 第一步：选完照片绝不直接贴墙上，而是强行押送进 UCrop 手术室！
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            // 制造一个极其干净的无菌盘子来装切好的照片
            val destinationUri = Uri.fromFile(
                File(filesDir, "npc_avatar_${System.currentTimeMillis()}.jpg")
            )

            val options = UCrop.Options()
            options.setCircleDimmedLayer(true) // 极其装逼的圆形取景框！
            options.setShowCropGrid(false) // 把碍眼的网格线拔了
            options.setToolbarTitle("裁剪头像") // 给手术室挂个牌子

            // 极其暴力的召唤术：死死焊住 1:1 的正方形比例！
            val uCropIntent = UCrop.of(uri, destinationUri)
                .withAspectRatio(1f, 1f)
                .withMaxResultSize(800, 800)
                .withOptions(options)
                .getIntent(this)

            cropImage.launch(uCropIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_profile)

        val db = DatabaseHelper(this).readableDatabase
        val cursor = db.query("MyProfile", null, null, null, null, null, null)
        if (cursor.moveToFirst()) {
            // ✅ 1. 安全读取我的名字和 ID
            findViewById<EditText>(R.id.etMyName).setText(cursor.getSafeString("myName"))
            findViewById<EditText>(R.id.etMyId).setText(cursor.getSafeString("myId"))

            // ✅ 2. 安全读取性别、生日、MBTI 和个人描述（新字段绝不卡死）
            findViewById<EditText>(R.id.etMyGender).setText(cursor.getSafeString("gender"))
            findViewById<EditText>(R.id.etMyBirthday).setText(cursor.getSafeString("birthday"))
            findViewById<EditText>(R.id.etMyMbti).setText(cursor.getSafeString("mbti"))
            findViewById<EditText>(R.id.etMyIdentity).setText(cursor.getSafeString("identity"))

            // ✅ 3. 安全读取我的头像路径
            val avatarStr = cursor.getSafeString("myAvatarUri")
            if (avatarStr.isNotEmpty()) {
                myAvatarUri =
                    if (avatarStr.startsWith("/")) Uri.fromFile(File(avatarStr))
                    else Uri.parse(avatarStr)

                try {
                    val bitmap =
                        if (avatarStr.startsWith("/")) {
                            android.graphics.BitmapFactory.decodeFile(avatarStr)
                        } else {
                            contentResolver.openInputStream(Uri.parse(avatarStr))
                                ?.use { input ->
                                    android.graphics.BitmapFactory.decodeStream(input)
                                }
                        }

                    if (bitmap != null) {
                        findViewById<ImageView>(R.id.ivEditMyAvatar).setImageBitmap(bitmap)
                    } else {
                        findViewById<ImageView>(R.id.ivEditMyAvatar).setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                } catch (_: Exception) {
                    findViewById<ImageView>(R.id.ivEditMyAvatar).setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
        cursor.close()

        findViewById<ImageView>(R.id.ivEditMyAvatar).setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.btnSaveProfile).setOnClickListener {
            val name = findViewById<EditText>(R.id.etMyName).text.toString()
            val myId = findViewById<EditText>(R.id.etMyId).text.toString()
            val gender = findViewById<EditText>(R.id.etMyGender).text.toString()
            val birthday = findViewById<EditText>(R.id.etMyBirthday).text.toString()
            val mbti = findViewById<EditText>(R.id.etMyMbti).text.toString()
            val identity = findViewById<EditText>(R.id.etMyIdentity).text.toString()

            if (name.isEmpty() || myId.isEmpty()) {
                Toast.makeText(this, "名字和ID总得填一个吧老板！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val writeDb = DatabaseHelper(this).writableDatabase
            writeDb.delete("MyProfile", null, null)

            val values = ContentValues().apply {
                put("myName", name)
                put("myId", myId)
                put("gender", gender)
                put("birthday", birthday)
                put("mbti", mbti)
                put("identity", identity)
                val avatarPath = myAvatarUri?.let { uri ->
                    when {
                        uri.toString().startsWith("file://") -> uri.path ?: ""
                        uri.toString().startsWith("/") -> uri.toString()
                        else -> uri.toString()
                    }
                } ?: ""

                put("myAvatarUri", avatarPath)
            }
            writeDb.insert("MyProfile", null, values)

            sendBroadcast(Intent("MY_PROFILE_CHANGED"))

            Toast.makeText(this, "已保存！", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
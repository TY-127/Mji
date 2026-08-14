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


class AddContactActivity : AppCompatActivity() {
    private var currentAvatarUri: Uri? = null
    private var editAiId: String? = null

    private val cropImage =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val resultUri = UCrop.getOutput(result.data!!)
                if (resultUri != null) {
                    currentAvatarUri = resultUri
                    try {
                        val inputStream = contentResolver.openInputStream(resultUri)
                        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        findViewById<ImageView>(R.id.ivSelectAvatar).setImageBitmap(bitmap)
                        inputStream?.close()
                    } catch (e: Exception) {
                    }
                }
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val destinationUri = Uri.fromFile(
                    File(filesDir, "avatar_${System.currentTimeMillis()}.jpg")
                )
                val options = UCrop.Options()
                options.setCircleDimmedLayer(true)
                options.setShowCropGrid(false)
                options.setToolbarTitle("裁剪角色头像")
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
        setContentView(R.layout.activity_add_contact)

        val etUserId = findViewById<EditText>(R.id.etUserId)
        val etRealName = findViewById<EditText>(R.id.etRealName)
        val etBirthday = findViewById<EditText>(R.id.etBirthday)
        val etIdentity = findViewById<EditText>(R.id.etIdentityInfo)
        val etPatience = findViewById<EditText>(R.id.etPatience)
        val spinnerRelationship = findViewById<android.widget.Spinner>(R.id.spinnerRelationship)
        val relationshipOptions = listOf("陌生人", "普通朋友", "好友", "恋人", "暗恋对象", "家人")
        val relAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            relationshipOptions
        )
        relAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRelationship.adapter = relAdapter

        val ivAvatar = findViewById<ImageView>(R.id.ivSelectAvatar)
        val btnSave = findViewById<Button>(R.id.btnSaveContact)

        ivAvatar.setOnClickListener { pickImage.launch(arrayOf("image/*")) }

        editAiId = intent.getStringExtra("EDIT_AI_ID")




        if (editAiId != null) {
            btnSave.text = "💾 确认篡改"
            try {
                val db = DatabaseHelper(this).readableDatabase
                val cursor =
                    db.query("Contacts", null, "userId=?", arrayOf(editAiId), null, null, null)
                if (cursor.moveToFirst()) {
                    // ✅ 1. 安全读取 userId 并填入输入框
                    etUserId.setText(cursor.getSafeString("userId"))
                    etUserId.isEnabled = false

                    // ✅ 2. 安全读取姓名、生日、人设信息
                    etRealName.setText(cursor.getSafeString("realName"))
                    etBirthday.setText(cursor.getSafeString("birthday"))
                    etIdentity.setText(cursor.getSafeString("identityInfo"))

                    // ✅ 3. 安全读取耐性值（Int），直接干掉多余的内嵌 try-catch，优雅省心
                    val patienceVal = cursor.getSafeInt("patience")
                    etPatience?.setText(patienceVal.toString())

                    // ✅ 4. 安全读取头像路径
                    val avatarStr = cursor.getSafeString("avatarUri")
                    if (avatarStr.isNotEmpty()) {
                        currentAvatarUri =
                            if (avatarStr.startsWith("/"))
                                Uri.fromFile(File(avatarStr))
                            else
                                Uri.parse(avatarStr)
                        try {
                            val bitmap = if (avatarStr.startsWith("/")) {
                                android.graphics.BitmapFactory.decodeFile(avatarStr)
                            } else {
                                contentResolver.openInputStream(Uri.parse(avatarStr))
                                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            }
                            if (bitmap != null) {
                                ivAvatar.setImageBitmap(bitmap)
                            } else {
                                ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
                            }
                        } catch (_: Exception) {
                            ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
                        }
                    }

                    // ✅ 直接干掉 try-catch！一行代码安全搞定，找不到字段或者为空都自动变成 "普通朋友"
                    val rel = cursor.getSafeString("relationship").ifEmpty { "普通朋友" }
                    val relIndex = relationshipOptions.indexOf(rel).takeIf { it >= 0 } ?: 0
                    spinnerRelationship.post {
                        spinnerRelationship.setSelection(relIndex)
                    }
                }
                cursor.close()
            } catch (e: Exception) {
            }
        }

        btnSave.setOnClickListener {
            val userId = etUserId.text.toString().trim()
            val realName = etRealName.text.toString().trim()
            val birthday = etBirthday.text.toString().trim()
            val identity = etIdentity.text.toString().trim()
            val patienceStr = etPatience.text.toString().trim()
            val patience = patienceStr.toIntOrNull() ?: 60
            if (userId.length > 50) {
                Toast.makeText(this, "用户ID过长", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (userId.isEmpty() || realName.isEmpty()) {
                Toast.makeText(this, "ID和真实姓名不能为空！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val avatarPath = if (currentAvatarUri != null) {
                val uriStr = currentAvatarUri.toString()
                when {
                    uriStr.startsWith("file://") -> uriStr.removePrefix("file://")
                    uriStr.startsWith("/") -> uriStr
                    else -> try {
                        val fileName = "avatar_${System.currentTimeMillis()}.jpg"
                        val destFile = File(filesDir, fileName)
                        contentResolver.openInputStream(currentAvatarUri!!)?.use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        destFile.absolutePath
                    } catch (_: Exception) {
                        uriStr
                    }
                }
            } else {
                if (editAiId != null) {
                    try {
                        val c = DatabaseHelper(this).readableDatabase.rawQuery(
                            "SELECT avatarUri FROM Contacts WHERE userId=?",
                            arrayOf(editAiId)
                        )
                        val v = if (c.moveToFirst()) c.getString(0) ?: "" else ""
                        c.close()
                        v
                    } catch (_: Exception) {
                        ""
                    }
                } else ""
            }

            val db = DatabaseHelper(this).writableDatabase

            if (editAiId == null) {
                val check = db.rawQuery(
                    "SELECT COUNT(*) FROM Contacts WHERE userId=?",
                    arrayOf(userId)
                )
                val exists = check.moveToFirst() && check.getInt(0) > 0
                check.close()

                if (exists) {
                    Toast.makeText(this, "这个用户ID已经存在，不能重复创建", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            val values = ContentValues().apply {
                if (editAiId == null) {
                    put("userId", userId)
                }

                put("realName", realName)
                put("birthday", birthday)
                put("identityInfo", identity)
                put("avatarUri", avatarPath)
                put("patience", patience)
                put("relationship", relationshipOptions[spinnerRelationship.selectedItemPosition])
            }
            val oldName = try {
                DatabaseHelper(this).readableDatabase.rawQuery(
                    "SELECT realName FROM Contacts WHERE userId=?",
                    arrayOf(editAiId ?: userId)
                ).use { c ->
                    if (c.moveToFirst()) c.getString(0) ?: "" else ""
                }
            } catch (_: Exception) { "" }
            if (editAiId != null) {
                db.update(
                    "Contacts",
                    values,
                    "userId=?",
                    arrayOf(editAiId)
                )
                // 耐心值变更时清除旧的待发队列，防止下次打开聊天立刻触发
                try {
                    db.execSQL(
                        "UPDATE PendingAiMessages SET isDone=1 WHERE aiId=? AND isDone=0",
                        arrayOf(editAiId)
                    )
                } catch (_: Exception) {}
                val newName = realName

                if (oldName.isNotEmpty() && newName.isNotEmpty() && oldName != newName) {
                    try {
                        db.execSQL(
                            """
            UPDATE MemoryBank
            SET memoryText = REPLACE(memoryText, ?, ?)
            WHERE aiId=?
            """.trimIndent(),
                            arrayOf(
                                oldName,
                                newName,
                                editAiId ?: userId
                            )
                        )
                    } catch (_: Exception) {}
                }
                Toast.makeText(this, "记忆与耐心篡改成功！", Toast.LENGTH_SHORT).show()
            } else {
                val result = db.insert("Contacts", null, values)
                if (result == -1L) {
                    Toast.makeText(this, "添加失败：数据库写入失败", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Toast.makeText(this, "极品联系人已入库！", Toast.LENGTH_SHORT).show()
            }

            finish()

        }
    }

}
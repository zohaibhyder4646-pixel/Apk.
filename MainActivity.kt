package com.gstraders.creditmanager

import android.app.Activity
import android.os.Bundle
import android.content.ContentValues
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Store(context: android.content.Context) : SQLiteOpenHelper(context, "gst_cr.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, phone TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL, date TEXT NOT NULL, kind TEXT NOT NULL, amount REAL NOT NULL, note TEXT DEFAULT '', FOREIGN KEY(customer_id) REFERENCES customers(id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun addCustomer(name: String, phone: String = "") { writableDatabase.insertWithOnConflict("customers", null, ContentValues().apply { put("name", name.trim()); put("phone", phone) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun addEntry(customerId: Long, kind: String, amount: Double, note: String = "") { writableDatabase.insert("entries", null, ContentValues().apply { put("customer_id", customerId); put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())); put("kind", kind); put("amount", amount); put("note", note) }) }
    fun customers(): List<Pair<Long,String>> { val out= mutableListOf<Pair<Long,String>>(); readableDatabase.rawQuery("SELECT id,name FROM customers ORDER BY name", null).use { while(it.moveToNext()) out.add(it.getLong(0) to it.getString(1)) }; return out }
    fun balance(id: Long): Double { readableDatabase.rawQuery("SELECT COALESCE(SUM(CASE WHEN kind='CREDIT' THEN amount ELSE -amount END),0) FROM entries WHERE customer_id=?", arrayOf(id.toString())).use { it.moveToFirst(); return it.getDouble(0) } }
    fun totals(): Pair<Double,Double> { readableDatabase.rawQuery("SELECT COALESCE(SUM(CASE WHEN kind='CREDIT' THEN amount ELSE 0 END),0), COALESCE(SUM(CASE WHEN kind='PAYMENT' THEN amount ELSE 0 END),0) FROM entries", null).use { it.moveToFirst(); return it.getDouble(0) to it.getDouble(1) } }
    fun statement(): String { val b=StringBuilder("Customer,Outstanding\n"); customers().forEach { b.append("\"${it.second.replace("\"","\"\"")}\",${balance(it.first)}\n") }; return b.toString() }
}

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var body: LinearLayout
    private val pickImage = 44
    private var extracted = ""
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); store=Store(this); showHome() }
    private fun base(title:String): LinearLayout {
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(18)); setBackgroundColor(0xFFF5F6F3.toInt()) }
        val heading=TextView(this).apply { text=title; textSize=23f; setTextColor(0xFF173B32.toInt()); setPadding(0,0,0,dp(14)); gravity=Gravity.CENTER_VERTICAL }
        root.addView(heading); body=root; return root
    }
    private fun button(label:String, action:()->Unit): Button = Button(this).apply { text=label; isAllCaps=false; setOnClickListener { action() }; layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) } }
    private fun label(text:String): TextView=TextView(this).apply { this.text=text; textSize=16f; setTextColor(0xFF24332E.toInt()); setPadding(0,dp(8),0,dp(8)) }
    private fun showHome() {
        val root=base("GS TRADERS\nCREDIT MANAGER")
        val t=store.totals(); val outstanding=t.first-t.second
        root.addView(label("Total credit recorded:  Rs. ${money(t.first)}")); root.addView(label("Payments received:  Rs. ${money(t.second)}")); root.addView(label("Net outstanding:  Rs. ${money(outstanding)}")); root.addView(label("Customers: ${store.customers().size}"))
        root.addView(button("Import CR sheet image (WhatsApp)" ){ chooseImages() })
        root.addView(button("Customers & balances"){ showCustomers() })
        root.addView(button("Add customer"){ addCustomerScreen() })
        root.addView(button("Add credit / payment"){ entryScreen() })
        root.addView(button("Export customer balances (CSV)"){ exportCsv() })
        root.addView(label("Offline storage • Data stays on this phone\nAgeing buckets require bill dates to be confirmed after import."))
        setContentView(root)
    }
    private fun money(v:Double)=String.format(Locale.US,"%,.2f",v)
    private fun addCustomerScreen() {
        val root=base("Add customer"); val name=EditText(this).apply { hint="Customer name" }; val phone=EditText(this).apply { hint="Phone (optional)" }
        root.addView(name); root.addView(phone); root.addView(button("Save customer"){ if(name.text.isNotBlank()){ store.addCustomer(name.text.toString(),phone.text.toString()); showCustomers() } else name.error="Enter a name" }); root.addView(button("Back"){showHome()}); setContentView(root)
    }
    private fun showCustomers() {
        val root=base("Customer accounts")
        val list=store.customers()
        if(list.isEmpty()) root.addView(label("No customers yet. Add a customer or import a CR image."))
        list.forEach { (id,name)-> root.addView(button("$name     Rs. ${money(store.balance(id))}"){ customerDetail(id,name) }) }
        root.addView(button("Add customer"){addCustomerScreen()}); root.addView(button("Back"){showHome()}); setContentView(root)
    }
    private fun customerDetail(id:Long,name:String) {
        val root=base(name); root.addView(label("Outstanding: Rs. ${money(store.balance(id))}")); root.addView(button("Record credit"){ amountDialog(id,"CREDIT") }); root.addView(button("Record payment"){ amountDialog(id,"PAYMENT") }); root.addView(button("Back to customers"){showCustomers()}); setContentView(root)
    }
    private fun amountDialog(id:Long,kind:String) {
        val box=EditText(this).apply { hint="Amount in PKR"; inputType=8194 }
        android.app.AlertDialog.Builder(this).setTitle(if(kind=="CREDIT") "Add credit" else "Record payment").setView(box).setPositiveButton("Save"){_,_-> val a=box.text.toString().toDoubleOrNull(); if(a!=null && a>0) store.addEntry(id,kind,a); showCustomers() }.setNegativeButton("Cancel",null).show()
    }
    private fun entryScreen() {
        val cs=store.customers(); if(cs.isEmpty()){ Toast.makeText(this,"Add a customer first",Toast.LENGTH_SHORT).show(); addCustomerScreen(); return }
        val root=base("Add transaction"); val spinner=Spinner(this); spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,cs.map{it.second}); val kind=Spinner(this); kind.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("CREDIT","PAYMENT")); val amount=EditText(this).apply { hint="Amount in PKR"; inputType=8194 }
        root.addView(label("Customer")); root.addView(spinner); root.addView(label("Type")); root.addView(kind); root.addView(amount); root.addView(button("Save transaction"){ val a=amount.text.toString().toDoubleOrNull(); if(a!=null && a>0){store.addEntry(cs[spinner.selectedItemPosition].first,kind.selectedItem.toString(),a); showHome()} else amount.error="Enter a valid amount" }); root.addView(button("Back"){showHome()}); setContentView(root)
    }
    private fun chooseImages(){ val i=Intent(Intent.ACTION_GET_CONTENT).apply { type="image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }; startActivityForResult(Intent.createChooser(i,"Select CR sheet image(s)"),pickImage) }
    @Deprecated("Deprecated Android callback kept for broad device compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){ super.onActivityResult(requestCode,resultCode,data); if(requestCode==pickImage && resultCode==Activity.RESULT_OK && data!=null){ val uris= mutableListOf<Uri>(); data.clipData?.let { for(i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }; if(uris.isEmpty()) data.data?.let{uris.add(it)}; if(uris.isNotEmpty()) readImages(uris) } }
    private fun readImages(uris:List<Uri>){ extracted=""; var left=uris.size; val rec=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS); uris.forEach { uri-> try { val img=InputImage.fromFilePath(this,uri); rec.process(img).addOnSuccessListener { extracted += "\n\n--- IMAGE ---\n"+it.text }.addOnCompleteListener { left--; if(left==0) showOcrReview() } } catch(e:Exception){ left--; if(left==0) showOcrReview() } } }
    private fun showOcrReview(){ val root=base("Review imported CR text"); root.addView(label("OCR reads the image on this phone. Check every name, date and amount before entering records.")); val text=EditText(this).apply { setText(extracted); minLines=12; gravity=Gravity.TOP; textSize=13f }; root.addView(text,LinearLayout.LayoutParams(-1,0,1f)); root.addView(button("Copy extracted text"){ val cb=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager; cb.setPrimaryClip(android.content.ClipData.newPlainText("CR OCR",text.text)); Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show() }); root.addView(button("Done"){showHome()}); setContentView(root) }
    private fun exportCsv(){ val content=store.statement(); val name="GSTraders_balances_${System.currentTimeMillis()}.csv"; val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,name); put(MediaStore.Downloads.MIME_TYPE,"text/csv") }; try { val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values); if(uri!=null) contentResolver.openOutputStream(uri)?.use{it.write(content.toByteArray())}; Toast.makeText(this,"Saved to Downloads/$name",Toast.LENGTH_LONG).show() } catch(e:Exception){ Toast.makeText(this,"Export failed: ${e.message}",Toast.LENGTH_LONG).show() } }
}

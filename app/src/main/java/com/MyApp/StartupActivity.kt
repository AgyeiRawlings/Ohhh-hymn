override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    prefs = getSharedPreferences("first_run", Context.MODE_PRIVATE)
    statusText = findViewById(R.id.statusText)

    val btn = findViewById<Button>(R.id.startButton)
    btn.setOnClickListener {
        if (checkAllPermissions()) {
            handleStartup()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    // Optional: show a hint to tap the button
    statusText.text = "Tap the button to start setup"
}

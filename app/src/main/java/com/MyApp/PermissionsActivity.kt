override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
        handleStartup()
    } else {
        Toast.makeText(this, "Permissions denied. App cannot continue.", Toast.LENGTH_LONG).show()
        statusText.text = "Permissions denied. App cannot run."
    }
}

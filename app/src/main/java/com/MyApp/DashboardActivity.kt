override fun onResume() {
    super.onResume()

    // After returning from settings
    if (checkAllPermissions() && checkNotificationAccess() && checkAccessibilityAccess()) {
        requestScreenCapture()
    }
}

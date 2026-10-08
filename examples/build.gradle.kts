plugins { application }

dependencies { implementation(project(":runtime")) }

application {
    mainClass = "com.numadisruptor.examples.TradingDemo"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

# extractor
extracting java documentation

# build project
./gradlew installDist
# extract file
# original java and html files are stored in the data folder
# generates classdoc.csv that contains class-level documentation split by sentence
./build/install/extractor/bin/extractor extract data

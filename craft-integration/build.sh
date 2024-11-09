# sudo pacman -S maven
mvn package

rm /home/reece/Desktop/interchaincraft/cosmossdk-minecraft-pvp-server/plugins/craft-integration*.jar
mv ./outputs/*.jar /home/reece/Desktop/interchaincraft/cosmossdk-minecraft-pvp-server/plugins

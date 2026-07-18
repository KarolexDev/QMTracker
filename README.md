# Chest Tracker (Unofficial port)
---
## **Unofficial port** for new versions Minecraft, as the original author hasn't updated the mod for a long time. [Original Mod](https://modrinth.com/mod/chest-tracker).
---
A client-sided storage system. Press **Y** to search for items, and the **GRAVE** key ``` ` ``` to open the GUI.

![An example image of Chest Tracker highlighting results, and showing names above chests](https://i.imgur.com/jfAfFDh.png)

![The main Chest Tracker GUI](https://i.imgur.com/45pBNFJ.png)

![A GIF recording on the inventory button to access the Chest Tracker GUI. Can now be moved around by users.](https://i.imgur.com/66sTTRg.gif)

[Other Images](https://modrinth.com/mod/chest-tracker-port/gallery)

## 📥 Requirements
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [YACL](https://modrinth.com/mod/yacl)

Chest Tracker embeds [Where Is It](https://modrinth.com/mod/where-is-it-port), [JackFredLib](https://github.com/ponuing/JackFredLib) and [Searchables](https://modrinth.com/mod/searchables).

## ⭐ Features
- Saving of items on the client, allowing it to work on Realms and multiplayer servers.
- Displays custom names of containers in the world (can be disabled under the Memory Bank Settings).
- Integration with:
  - [REI](https://modrinth.com/mod/rei), [JEI](https://modrinth.com/mod/jei), and [EMI](https://modrinth.com/mod/emi) via Where Is It; see it's usage section
  - [Shulker Box Tooltip](https://modrinth.com/mod/shulkerboxtooltip) - Show Ender Chest contents on the client
  - [WTHIT](https://modrinth.com/mod/wthit) & [Jade](https://modrinth.com/mod/jade) - Show contents of container you're looking at. Contains it's own plugin settings.
  - [Litematica](https://modrinth.com/mod/litematica) - Quick search buttons, and adding Ender Chest and nearby containers to the material list.
- Custom handling for:
  - Hypixel Skyblock (private island + ender chest).
  - Hypixel SMP
## 📖 Usage
Press Y to search by an Item Stack; this uses Where Is It's keybind.

Press GRAVE ```[ ` ]``` to open the main GUI. In the GUI, click an item to search for it in your current dimension. Use the search bar and it's various filters to narrow down your search.

## 🛠️ Development Setup
This project depends on the `JackFredLib` and `WhereIsIt` libraries, hosted on GitHub Packages, which requires authentication to download even public packages. To build/sync locally:

1. Copy `.env.example` to `.env`.
2. Create a GitHub Personal Access Token (classic) with the `read:packages` scope: https://github.com/settings/tokens
3. Fill in `GITHUB_ACTOR` (your GitHub username) and `GITHUB_TOKEN` (the token) in `.env`.
4. Sync/build with Gradle as normal.

# Ọfọ & Bronze: Chronicles of Nri

An example Stratum plugin, and a template for your own: a pen-and-paper
campaign played inside the action game. It adds two classes, a masquerade
spirit, a page of lore, and four rites a player can roll at the Table for
power in the fight.

It depends on the built-in `igbo` pack for its weapons, skills and regions.

Package it into a `.stratum` file anyone can install:

```sh
./gradlew :tools:artpreview:packPlugin --args="examples/plugins/nri-chronicles build/nri-chronicles.stratum"
```

See [`docs/PLUGINS.md`](../../../docs/PLUGINS.md) for every field.

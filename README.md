# Lombok Builder Helper

Displays an error when non-nullable annotated fields have *not* been included
in calls to a Lombok **@Builder** class.

On error, this plugin will provide a quick-fix that will add all fields annotated not nullable 
to the builder call. 

The inspection settings can be modified in Settings | Editor | Inspections under the
name 'Lombok builder is missing @NonNull fields'

***Note: This plugin only works with the annotations provided by Lombok, Jetbrains and javax***

<p align="center"><img src="https://i.imgur.com/vqfVZYa.gif" alt="demo" style="width: auto;height: auto;max-width: 90%; max-height: 90%;"></p>

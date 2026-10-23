package dev.airun.storagefill;

public final class JeiCompatibility {
    public static final boolean CONTROLLER_BUTTONS = present("mezz/jei/api/gui/buttons/IIconButtonController.class");
    public static final boolean CONTEXT_TRANSFER = present("mezz/jei/api/recipe/transfer/IRecipeTransferContext.class");

    private JeiCompatibility() { }

    private static boolean present(String resource) {
        return JeiCompatibility.class.getClassLoader().getResource(resource) != null;
    }
}

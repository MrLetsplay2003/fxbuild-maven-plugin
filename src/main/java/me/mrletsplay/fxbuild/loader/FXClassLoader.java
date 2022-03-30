package me.mrletsplay.fxbuild.loader;

import java.net.URL;
import java.net.URLClassLoader;

public class FXClassLoader extends URLClassLoader {

	private ClassLoader parent;
	private boolean debug;
	
	public FXClassLoader(URL[] urls, ClassLoader parent) {
		super(urls, (ClassLoader) null);
		this.parent = parent;
		this.debug = System.getProperty("fxloader.debugClassLoader") != null;
	}
	
	@Override
	protected Class<?> findClass(String name) throws ClassNotFoundException {
		try {
			Class<?> c = super.findClass(name);
			if(debug) System.out.println(name + " -> " + c);
			return c;
		}catch(ClassNotFoundException e) {
			return parent.loadClass(name);
		}
	}
	
	@Override
	public Class<?> loadClass(String name) throws ClassNotFoundException {
		Class<?> c = super.loadClass(name);
		if(debug) System.out.println(name + " -> " + c);
		return c;
	}
	
	@Override
	protected Class<?> findClass(String moduleName, String name) {
		Class<?> c = super.findClass(moduleName, name);
		if(debug) System.out.println(moduleName + "/" + name + " -> " + c);
		return c;
	}
	
}

////////////////////////////////////////////////////////////////////////////////
// checkstyle: Checks Java source code for adherence to a set of rules.
// Copyright (C) 2001-2003  Oliver Burn
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
////////////////////////////////////////////////////////////////////////////////
package com.puppycrawl.tools.checkstyle.checks.coding;

import com.puppycrawl.tools.checkstyle.api.Check;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * <p>Checks that code doesn't rely on the "this." default, i.e. references to
 * instance variables and methods of the present object are explicitly of
 * the form "this.varName" or "this.methodName(args)".</P>
 * <p>Examples of use:
 * <pre>
 * &lt;module name="RequireThis"/&gt;
 * </pre>
 * The following isn't implemented yet:
 * <pre>
 * &lt;module name="RequireThis"&gt;
 *   &lt;property name="kinds" value="variables,methods"&gt;\
 * &lt;/module&gt;
 * </pre>
 * ("variables,methods" is the default; either one can be specified by
 * itself, or you could even specify the empty string, in which case
 * the module would have no effect.)
 * </p>
 * <p>Limitations: I'm not currently doing anything about static variables
 * or catch-blocks.  Static methods invoked on a class name seem to be OK;
 * both the class name and the method name have a DOT parent.
 * Non-static methods invoked on either this or a variable name seem to be
 * OK, likewise.</p>
 * <p>Much of the code for this check was cribbed from Rick Giles's
 * <code>HiddenFieldCheck</code>.</p>
 *
 * @author Stephen Bloch
 */
public class RequireThisCheck extends Check
{
    /** Stack of varibale declaration frames. */
    private FrameStack mFrames;

    /** Creates new instance of the check. */
    public RequireThisCheck()
    {
    }

    /** {@inheritDoc} */
    public int[] getDefaultTokens()
    {
        return new int[] {
            TokenTypes.IDENT,
            TokenTypes.VARIABLE_DEF,
            TokenTypes.PARAMETER_DEF,
            TokenTypes.CLASS_DEF,
            TokenTypes.METHOD_DEF,
            TokenTypes.CTOR_DEF,
            TokenTypes.SLIST,
            // TokenTypes.LITERAL_CATCH also introduces a parameter name
        };
    }

    /** {@inheritDoc} */
    public int[] getRequiredTokens()
    {
        return getDefaultTokens();
    }

    /** {@inheritDoc} */
    public void beginTree(DetailAST aRootAST)
    {
        this.mFrames = new FrameStack();
    }

    /** {@inheritDoc} */
    public void leaveToken(DetailAST aAST)
    {
        switch (aAST.getType()) {
        case TokenTypes.CLASS_DEF :
        case TokenTypes.SLIST :
        case TokenTypes.METHOD_DEF :
        case TokenTypes.CTOR_DEF :
            this.mFrames.leave();
            break;
        case TokenTypes.PARAMETER_DEF :
        case TokenTypes.VARIABLE_DEF :
        case TokenTypes.IDENT :
            // do nothing
            break;
        default :
            log(aAST, "require.this.unexpected.leaving",
                new Integer(aAST.getType()));
        }
    }

    /** {@inheritDoc} */
    public void visitToken(DetailAST aAST)
    {
        switch (aAST.getType()) {
        case TokenTypes.PARAMETER_DEF :
        case TokenTypes.VARIABLE_DEF : {
            final DetailAST nameAST = aAST.findFirstToken(TokenTypes.IDENT);
            this.mFrames.current().addName(nameAST.getText());
            break;
        }
        case TokenTypes.CLASS_DEF : {
            final DetailAST nameAST = aAST.findFirstToken(TokenTypes.IDENT);
            this.mFrames.current().addName(nameAST.getText());
            this.mFrames.enter(new ClassFrame());
            break;
        }
        case TokenTypes.SLIST :
            this.mFrames.enter(new BlockFrame());
            break;
        case TokenTypes.METHOD_DEF :
        case TokenTypes.CTOR_DEF :
            this.mFrames.enter(new MethodFrame());
            break;
        case TokenTypes.IDENT :
            processIDENT(aAST);
            break;
        default:
            log(aAST, "require.this.unexpected.visit",
                new Integer(aAST.getType()));
        } // end switch
    } // end visitToken

    /**
     * Checks if a given IDENT is method call or field name which
     * require explicit <code>this</code> qualifier.
     *
     * @param aAST IDENT to check.
     */
    private void processIDENT(DetailAST aAST)
    {
        int parentType = aAST.getParent().getType();

        if (parentType == TokenTypes.METHOD_CALL) {
            log(aAST, "require.this.method", aAST.getText());
            return;
        }
        if (parentType == TokenTypes.DOT
            && aAST.getPreviousSibling() != null)
        {
            // it's the method name in a method call; no problem
            return;
        }
        if (parentType == TokenTypes.TYPE
            || parentType == TokenTypes.LITERAL_NEW)
        {
            // it's a type name; no problem
            return;
        }
        if (parentType == TokenTypes.VARIABLE_DEF
            || parentType == TokenTypes.CTOR_DEF
            || parentType == TokenTypes.METHOD_DEF
            || parentType == TokenTypes.CLASS_DEF
            || parentType == TokenTypes.PARAMETER_DEF)
        {
            // it's being declared; no problem
            return;
        }

        final LexicalFrame declared = this.mFrames.findFrame(aAST.getText());
        if (declared == null) {
            log(aAST, "require.this.unfound.variable", aAST.getText());
        }
        else if (declared instanceof ClassFrame) {
            log(aAST, "require.this.variable", aAST.getText());
        }
    }
} // end class RequireThis

/**
 * A declaration frame.
 * @author Stephen Bloch
 * June 19, 2003
 */
abstract class LexicalFrame
{
    /** Set of name of variables declared in this frame. */
    private HashSet mVarNames;

    /** constructor -- invocable only via super() from subclasses */
    protected LexicalFrame()
    {
        mVarNames = new HashSet();
    }

    /** add a name to the frame.
     * @param aNameToAdd  the name we're adding
     */
    void addName(String aNameToAdd)
    {
        this.mVarNames.add(aNameToAdd);
    }

    /** check whether the frame contains a given name.
     * @param aNameToFind  the name we're looking for
     * @return whether it was found
     */
    boolean contains(String aNameToFind)
    {
        return this.mVarNames.contains(aNameToFind);
    }
}

/**
 * The global frame; should hold only class names.
 * @author Stephen Bloch
 */
class GlobalFrame extends LexicalFrame
{
    /** Create new instance of hte frame. */
    GlobalFrame()
    {
        super();
    }
}

/**
 * A frame initiated at method definition; holds parameter names.
 * @author Stephen Bloch
 */
class MethodFrame extends LexicalFrame
{
    /** Create new instance of hte frame. */
    MethodFrame()
    {
        super();
    }
}

/**
 * A frame initiated at class definition; holds instance variable
 * names.  For the present, I'm not worried about other class names,
 * method names, etc.
 * @author Stephen Bloch
 */
class ClassFrame extends LexicalFrame
{
    /** Create new instance of hte frame. */
    ClassFrame()
    {
        super();
    }
}

/**
 * A frame initiated on entering a statement list; holds local variable
 * names.  For the present, I'm not worried about other class names,
 * method names, etc.
 * @author Stephen Bloch
 */
class BlockFrame extends LexicalFrame
{
    /** Create new instance of hte frame. */
    BlockFrame()
    {
        super();
    }
}

/**
 * A stack of LexicalFrames.  Standard issue....
 * @author Stephen Bloch
 */
class FrameStack
{
    /** List of lexical frames. */
    private LinkedList mFrameList;

    /** Creates an empty FrameStack. */
    FrameStack()
    {
        mFrameList = new LinkedList();
        this.enter(new GlobalFrame());
    }

    /**
     * Enter a scope, i.e. push a frame on the stack.
     * @param aNewFrame  the already-created frame to push
     */
    void enter(LexicalFrame aNewFrame)
    {
        mFrameList.addFirst (aNewFrame);
    }

    /** Leave a scope, i.e. pop a frame from the stack.  */
    void leave()
    {
        mFrameList.removeFirst();
    }

    /**
     * Get current scope, i.e. top frame on the stack.
     * @return the current frame
     */
    LexicalFrame current()
    {
        return (LexicalFrame) mFrameList.getFirst();
    }

    /**
     * Search this and containing frames for a given name.
     * @param aNameToFind the name we're looking for
     * @return the frame in which it was found, or null if not found
     */
    LexicalFrame findFrame (String aNameToFind)
    {
        final Iterator it = mFrameList.iterator();
        while (it.hasNext()) {
            final LexicalFrame thisFrame = (LexicalFrame) it.next();
            if (thisFrame.contains(aNameToFind)) {
                return thisFrame;
            }
        }
        return null;
    }
}
